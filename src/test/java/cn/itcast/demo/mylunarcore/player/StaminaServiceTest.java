package cn.itcast.demo.mylunarcore.player;

import cn.itcast.demo.mylunarcore.common.PeriodicResetService;
import cn.itcast.demo.mylunarcore.economy.WalletApplicationService;
import cn.itcast.demo.mylunarcore.model.PlayerEntity;
import cn.itcast.demo.mylunarcore.repo.PlayerDataRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 体力核心：消耗不足拒绝、购买扣费成功。
 */
@ExtendWith(MockitoExtension.class)
class StaminaServiceTest {

    @Mock
    JdbcTemplate jdbc;
    @Mock
    PlayerDataRepository playerDataRepository;
    @Mock
    WalletApplicationService walletApplicationService;
    @Mock
    PeriodicResetService periodicResetService;
    @Mock
    ObjectProvider<cn.itcast.demo.mylunarcore.guild.GuildTechService> guildTechProvider;

    private final AtomicInteger stamina = new AtomicInteger(100);
    private StaminaService service;

    @BeforeEach
    void setUp() {
        lenient().when(guildTechProvider.getIfAvailable()).thenReturn(null);
        lenient().doAnswer(inv -> null).when(periodicResetService).registerDaily(any());
        lenient().when(jdbc.update(anyString(), any(), any())).thenReturn(1);
        lenient().when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(1);
        lenient().when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any()))
                .thenReturn(java.util.List.of());

        when(playerDataRepository.loadPlayerByUid(org.mockito.ArgumentMatchers.anyLong())).thenAnswer(inv -> {
            PlayerEntity p = new PlayerEntity();
            p.setUid(((Number) inv.getArgument(0)).longValue());
            p.setStamina(stamina.get());
            return p;
        });
        lenient().when(jdbc.update(eq("UPDATE player SET stamina = ?, updated_at = NOW() WHERE uid = ?"),
                any(), any())).thenAnswer(inv -> {
            stamina.set(((Number) inv.getArgument(1)).intValue());
            return 1;
        });

        service = new StaminaService(jdbc, playerDataRepository, walletApplicationService,
                new ObjectMapper(), periodicResetService, guildTechProvider, "data");
    }

    @Test
    void tryConsumeRejectsWhenInsufficient() {
        stamina.set(10);
        StaminaService.ConsumeResult r = service.tryConsume(1, 40);
        assertFalse(r.ok());
        assertEquals(2, r.retcode());
    }

    @Test
    void tryConsumeSucceedsAndReduces() {
        stamina.set(80);
        StaminaService.ConsumeResult r = service.tryConsume(1, 40);
        assertTrue(r.ok());
        assertEquals(40, r.remaining());
    }

    @Test
    void buyStaminaFailsWhenWalletRejects() {
        stamina.set(100);
        when(walletApplicationService.deduct(eq(1), anyInt(), anyInt(), anyString()))
                .thenReturn(new WalletApplicationService.WalletChangeResult(false, Map.of(), "stamina_buy"));
        StaminaService.ConsumeResult r = service.buyStamina(1);
        assertFalse(r.ok());
        assertEquals(3, r.retcode());
    }

    @Test
    void buyStaminaGrantsWhenPaid() {
        stamina.set(100);
        when(walletApplicationService.deduct(eq(1), anyInt(), anyInt(), anyString()))
                .thenReturn(new WalletApplicationService.WalletChangeResult(true, Map.of(101, 1), "stamina_buy"));
        StaminaService.ConsumeResult r = service.buyStamina(1);
        assertTrue(r.ok());
        assertTrue(r.remaining() > 100);
    }
}
