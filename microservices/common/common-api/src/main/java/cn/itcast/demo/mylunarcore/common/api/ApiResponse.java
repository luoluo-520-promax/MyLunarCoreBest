// 声明当前类所在的包：公共 API 响应模型
package cn.itcast.demo.mylunarcore.common.api;

/**
 * 统一 HTTP/REST 接口返回体（Java 16+ record：不可变数据载体）。
 *
 * @param <T> data 字段的业务数据类型
 * @param code    业务状态码，0 表示成功
 * @param message 人类可读提示信息
 * @param data    实际载荷，失败时通常为 null
 */
public record ApiResponse<T>(int code, String message, T data) {

    /**
     * 构造成功响应。
     *
     * @param data 要返回给客户端的数据
     * @return code=0、message=OK 的响应对象
     */
    public static <T> ApiResponse<T> ok(T data) {
        // 使用 0 作为成功码，消息固定为 OK
        return new ApiResponse<>(0, "OK", data);
    }

    /**
     * 构造失败响应（无 data）。
     *
     * @param code    非 0 错误码
     * @param message 错误说明
     * @return data 为 null 的响应对象
     */
    public static <T> ApiResponse<T> fail(int code, String message) {
        // 失败时不携带业务数据
        return new ApiResponse<>(code, message, null);
    }
}
