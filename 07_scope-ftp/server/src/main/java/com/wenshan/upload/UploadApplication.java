package com.wenshan.upload;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * ScopeFTP-server 入口。
 *
 * <p>除了环境变量 {@code UPLOAD_APP_KEY}/{@code UPLOAD_APP_SECRET}，启动 Jar 时也可以直接传
 * {@code --app-key}/{@code --app-secret}（会映射到第一个预配发客户端的 Spring 属性）；
 * 两者都不传时使用 application.yml 内置默认值。</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class UploadApplication {

    private static final String APP_KEY_FLAG = "--app-key";
    private static final String APP_SECRET_FLAG = "--app-secret";
    private static final String APP_KEY_PROPERTY = "--app.security.clients[0].app-key";
    private static final String APP_SECRET_PROPERTY = "--app.security.clients[0].app-secret";

    public static void main(String[] args) {
        SpringApplication.run(UploadApplication.class, normalizeCredentialArgs(args));
    }

    /**
     * 把简写参数映射为正式 Spring 属性，保证命令行优先级高于 YAML/环境变量。
     * 支持 {@code --app-key value} 与 {@code --app-key=value} 两种写法。
     */
    static String[] normalizeCredentialArgs(String[] args) {
        if (args == null || args.length == 0) {
            return new String[0];
        }
        List<String> normalized = new ArrayList<>(args.length);
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (APP_KEY_FLAG.equals(arg) || APP_SECRET_FLAG.equals(arg)) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException(arg + " 缺少值");
                }
                String property = APP_KEY_FLAG.equals(arg) ? APP_KEY_PROPERTY : APP_SECRET_PROPERTY;
                normalized.add(property + "=" + args[++i]);
            } else if (arg.startsWith(APP_KEY_FLAG + "=")) {
                normalized.add(APP_KEY_PROPERTY + arg.substring(APP_KEY_FLAG.length()));
            } else if (arg.startsWith(APP_SECRET_FLAG + "=")) {
                normalized.add(APP_SECRET_PROPERTY + arg.substring(APP_SECRET_FLAG.length()));
            } else {
                normalized.add(arg);
            }
        }
        return normalized.toArray(String[]::new);
    }
}
