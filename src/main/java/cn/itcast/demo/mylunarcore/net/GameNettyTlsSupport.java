// 为游戏 TCP 构建 Netty TLS 上下文
package cn.itcast.demo.mylunarcore.net;

// 应用 TLS 配置项
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
// Netty 服务端 SSL 上下文
import io.netty.handler.ssl.SslContext;
// 根据密钥库构建 SslContext 的 Builder
import io.netty.handler.ssl.SslContextBuilder;

// 从密钥库加载服务端证书私钥
import javax.net.ssl.KeyManagerFactory;
// 读取密钥库流
import java.io.InputStream;
// 从文件系统读取密钥库路径
import java.nio.file.Files;
// Path：定位 keystore 文件
import java.nio.file.Path;
// Java 密钥库类型（如 PKCS12、JKS）
import java.security.KeyStore;

/**
 * Netty TCP 侧 TLS：与 HTTPS 同属 TLS 协议族——握手阶段通过非对称密码（如 ECDHE、RSA）协商临时会话密钥，
 * 后续业务数据在记录层用对称加密（如 AES-GCM）传输。
 */
public final class GameNettyTlsSupport {

    /** 工具类禁止实例化 */
    private GameNettyTlsSupport() {
    }

    /**
     * 根据配置构建服务端 {@link SslContext}，供 Pipeline 首节点使用。
     */
    public static SslContext serverSslContext(LunarCoreProperties.TlsProperties tls) throws Exception {
        KeyManagerFactory kmf = buildKeyManagerFactory(tls); // 构造密钥管理器
        return SslContextBuilder.forServer(kmf).build(); // 构建仅供服务端使用的 SslContext
    }

    /**
     * 从密钥库构造 {@link KeyManagerFactory}。
     */
    private static KeyManagerFactory buildKeyManagerFactory(LunarCoreProperties.TlsProperties tls) throws Exception {
        KeyStore ks = KeyStore.getInstance(tls.getKeyStoreType()); // 按类型创建空 KeyStore
        char[] storePw = tls.getKeyStorePassword().toCharArray(); // 密钥库口令
        try (InputStream in = openKeyStoreStream(tls.getKeyStore())) {
            ks.load(in, storePw); // 加载证书与私钥条目
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); // SunJSSE 默认算法名
        char[] keyPw = tls.getKeyPassword() != null
                ? tls.getKeyPassword().toCharArray()
                : storePw; // 未单独配置 key 密码则与 store 密码相同
        kmf.init(ks, keyPw);
        return kmf;
    }

    /**
     * 打开密钥库输入流：支持 {@code classpath:} 前缀或文件路径。
     */
    private static InputStream openKeyStoreStream(String keyStore) throws Exception {
        if (keyStore.startsWith("classpath:")) { // 类路径资源前缀
            String path = keyStore.substring("classpath:".length()); // 去掉前缀得到相对路径
            InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path); // 通过 TCCL 读取资源流
            if (in == null) {
                throw new IllegalStateException("Classpath keystore not found: " + path);
            }
            return in;
        }
        return Files.newInputStream(Path.of(keyStore)); // 文件系统绝对/相对路径
    }
}
