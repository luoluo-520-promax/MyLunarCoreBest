package cn.itcast.demo.mylunarcore.activity;

import cn.itcast.demo.mylunarcore.common.ClusterJobLock;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 活动 CDN 资源版本管理（与 {@link cn.itcast.demo.mylunarcore.common.ActivityScheduleService} 开关分离）。
 * 支持运维预加载配置到 Redis，活动开始时刻由 ClusterJobLock 原子 activate。
 */
@Service
public class ActivityResourceService {

    private static final Logger log = LoggerFactory.getLogger(ActivityResourceService.class);
    private static final String REDIS_PRELOAD_PREFIX = "lunar:activity:preload:";
    private static final String REDIS_ACTIVE_PREFIX = "lunar:activity:active:";

    public record ResourceMeta(int versionActivityId, String cdnBundleVersion, String cdnManifestUrl,
                               boolean preloadReady, boolean active, Instant openAt, Instant closeAt) {}

    public record PreloadResult(boolean ok, String message, String cacheKey) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ClusterJobLock clusterJobLock;
    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final Map<Integer, ResourceMeta> memory = new ConcurrentHashMap<>();

    public ActivityResourceService(JdbcTemplate jdbc,
                                   ObjectMapper objectMapper,
                                   ClusterJobLock clusterJobLock,
                                   ObjectProvider<StringRedisTemplate> redisProvider) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clusterJobLock = clusterJobLock;
        this.redisProvider = redisProvider;
    }

    @PostConstruct
    public void warmFromDb() {
        try {
            List<ResourceMeta> list = jdbc.query("""
                    SELECT version_activity_id, cdn_bundle_version, cdn_manifest_url,
                           preload_ready, active, open_at, close_at
                    FROM activity_resource_version
                    """, (rs, i) -> new ResourceMeta(
                    rs.getInt(1), rs.getString(2), rs.getString(3),
                    rs.getInt(4) == 1, rs.getInt(5) == 1,
                    rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant(),
                    rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant()));
            for (ResourceMeta m : list) {
                memory.put(m.versionActivityId(), m);
            }
        } catch (Exception e) {
            log.debug("activity_resource_version warm skipped: {}", e.getMessage());
        }
    }

    /**
     * 运维预加载：写入 DB + Redis，不立即激活。
     */
    @Transactional
    public PreloadResult preload(int versionActivityId, String cdnBundleVersion, String cdnManifestUrl,
                                 String configJson, Instant activateAt, String operator) {
        if (versionActivityId <= 0 || cdnBundleVersion == null || cdnBundleVersion.isBlank()) {
            return new PreloadResult(false, "invalid_args", "");
        }
        String cacheKey = "va:" + versionActivityId + ":" + cdnBundleVersion;
        try {
            jdbc.update("""
                    INSERT INTO activity_resource_version
                    (version_activity_id, cdn_bundle_version, cdn_manifest_url, preload_ready, active,
                     open_at, config_json)
                    VALUES (?, ?, ?, 1, 0, ?, CAST(? AS JSON))
                    ON DUPLICATE KEY UPDATE cdn_bundle_version=VALUES(cdn_bundle_version),
                      cdn_manifest_url=VALUES(cdn_manifest_url), preload_ready=1, active=0,
                      open_at=VALUES(open_at), config_json=VALUES(config_json), updated_at=NOW(3)
                    """, versionActivityId, cdnBundleVersion,
                    cdnManifestUrl == null ? "" : cdnManifestUrl,
                    activateAt == null ? null : java.sql.Timestamp.from(activateAt),
                    configJson == null ? "{}" : configJson);
            jdbc.update("""
                    INSERT INTO activity_preload_cache (cache_key, payload_json, activate_at, activated, created_by)
                    VALUES (?, ?, ?, 0, ?)
                    ON DUPLICATE KEY UPDATE payload_json=VALUES(payload_json),
                      activate_at=VALUES(activate_at), activated=0, created_by=VALUES(created_by)
                    """, cacheKey, configJson == null ? "{}" : configJson,
                    activateAt == null ? null : java.sql.Timestamp.from(activateAt),
                    operator == null ? "" : operator);
        } catch (Exception e) {
            return new PreloadResult(false, e.getMessage(), cacheKey);
        }
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis != null) {
            try {
                Map<String, Object> payload = new HashMap<>();
                payload.put("versionActivityId", versionActivityId);
                payload.put("cdnBundleVersion", cdnBundleVersion);
                payload.put("cdnManifestUrl", cdnManifestUrl);
                payload.put("config", configJson);
                payload.put("activateAt", activateAt == null ? null : activateAt.toString());
                redis.opsForValue().set(REDIS_PRELOAD_PREFIX + cacheKey,
                        objectMapper.writeValueAsString(payload), Duration.ofDays(21));
            } catch (Exception e) {
                log.warn("redis preload write failed: {}", e.toString());
            }
        }
        memory.put(versionActivityId, new ResourceMeta(versionActivityId, cdnBundleVersion,
                cdnManifestUrl == null ? "" : cdnManifestUrl, true, false, activateAt, null));
        return new PreloadResult(true, "preloaded", cacheKey);
    }

    /** 原子激活：ClusterJobLock 保证多节点只切换一次。 */
    public boolean activate(int versionActivityId) {
        return Boolean.TRUE.equals(clusterJobLock.tryRun(
                "activity-activate:" + versionActivityId, Duration.ofMinutes(2), () -> {
                    jdbc.update("""
                            UPDATE activity_resource_version SET active=1, updated_at=NOW(3)
                            WHERE version_activity_id=? AND preload_ready=1
                            """, versionActivityId);
                    jdbc.update("""
                            UPDATE activity_preload_cache SET activated=1
                            WHERE cache_key LIKE ? AND activated=0
                            """, "va:" + versionActivityId + ":%");
                    ResourceMeta prev = memory.get(versionActivityId);
                    if (prev != null) {
                        memory.put(versionActivityId, new ResourceMeta(prev.versionActivityId(),
                                prev.cdnBundleVersion(), prev.cdnManifestUrl(), true, true,
                                prev.openAt(), prev.closeAt()));
                    }
                    StringRedisTemplate redis = redisProvider.getIfAvailable();
                    if (redis != null && prev != null) {
                        try {
                            redis.opsForValue().set(REDIS_ACTIVE_PREFIX + versionActivityId,
                                    prev.cdnBundleVersion(), Duration.ofDays(30));
                        } catch (Exception ignored) {
                        }
                    }
                    log.info("activity activated id={}", versionActivityId);
                }));
    }

    @Scheduled(fixedDelayString = "${lunarcore.activity.activate-scan-ms:30000}")
    public void scanAndActivateDue() {
        Instant now = Instant.now();
        try {
            jdbc.query("""
                    SELECT version_activity_id FROM activity_resource_version
                    WHERE preload_ready=1 AND active=0 AND open_at IS NOT NULL AND open_at <= ?
                    """, rs -> {
                while (rs.next()) {
                    activate(rs.getInt(1));
                }
                return null;
            }, java.sql.Timestamp.from(now));
        } catch (Exception e) {
            log.debug("activate scan skipped: {}", e.getMessage());
        }
    }

    public ResourceMeta get(int versionActivityId) {
        return memory.get(versionActivityId);
    }

    public List<ResourceMeta> listAll() {
        return List.copyOf(memory.values());
    }
}
