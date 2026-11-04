package com.wenshan.upload;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import com.wenshan.upload.config.CredentialPreflight;

/**
 * ScopeFTP-server 入口。
 *
 * <p>环境差异由各环境文件 {@code application-<env>.yml} 决定（dev / docker /
 * xiaoshan / xihu …），启动时用 {@code --spring.profiles.active=<env>} 选择，未指定默认 dev。
 * 凭证可用环境变量 {@code UPLOAD_APP_KEY}/{@code UPLOAD_APP_SECRET} 注入，也可以直接传
 * {@code --app-key}/{@code --app-secret}（会映射到第一个预配发客户端的 Spring 属性），
 * 或在当前环境文件里配置。三者都没有时启动失败（凭证没有内置默认值）。</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class UploadApplication {

    private static final String APP_KEY_FLAG = "--app-key";
    private static final String APP_SECRET_FLAG = "--app-secret";
    private static final String APP_KEY_PROPERTY = "--app.security.clients[0].app-key";
    private static final String APP_SECRET_PROPERTY = "--app.security.clients[0].app-secret";

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(UploadApplication.class);
        // 在创建 Bean 之前先检查凭证，缺 key/secret 时只打印一条可操作提示，不刷 Spring 堆栈。
        application.addInitializers(new CredentialPreflight());
        application.run(normalizeCredentialArgs(args));
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
