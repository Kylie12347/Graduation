package cn.graduation.cabinet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** IDEA中运行此main方法即可同时启动REST接口和网页管理台。 */
@SpringBootApplication
public class CabinetApplication {
    public static void main(String[] args) {
        SpringApplication.run(CabinetApplication.class, args);
    }
}
