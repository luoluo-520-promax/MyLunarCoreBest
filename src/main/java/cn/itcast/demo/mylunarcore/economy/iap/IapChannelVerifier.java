package cn.itcast.demo.mylunarcore.economy.iap;

/**
 * 按渠道拆分的收据验签接口。
 */
public interface IapChannelVerifier {

    /** 渠道名（如 apple / google / mock），大小写不敏感匹配。 */
    String channel();

    /** 是否已具备真实验签所需配置（密钥/包名等）。 */
    boolean isConfigured();

    IapVerifyGateway.VerifyResult verify(String orderId, String skuId, String receipt);
}
