package cn.itcast.demo.mylunarcore.match;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 匹配服务脚手架：后续承接队列、房间与跨节点匹配；当前提供健康检查与占位 API。
 */
@SpringBootApplication
public class MatchServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MatchServiceApplication.class, args);
    }
}
