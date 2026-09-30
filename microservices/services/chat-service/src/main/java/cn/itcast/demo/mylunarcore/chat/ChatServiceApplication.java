package cn.itcast.demo.mylunarcore.chat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 聊天服务脚手架：世界频道 / 私聊跨节点路由；正式逻辑迁自 monolith ChatService。
 */
@SpringBootApplication
public class ChatServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatServiceApplication.class, args);
    }
}
