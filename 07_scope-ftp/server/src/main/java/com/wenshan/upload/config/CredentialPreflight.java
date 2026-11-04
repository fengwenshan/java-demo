package com.wenshan.upload.config;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * 启动前的凭证预检：在 Spring 创建 Bean 之前发现“缺少 AppKey/AppSecret”，
 * 打印一条可操作的提示并退出，避免用户看到几十行 BeanCreationException 堆栈。
 *
 * <p>直接 {@code java -jar ScopeFTP-server-*.jar} 启动时也生效。检查的是已经加载完成的
 * Environment（因此支持命令行、环境变量、application-*.yml 三种来源），只拦“凭证为空”，
 * AppSecret 长度等规则仍由 {@link AppProperties} 负责。</p>
 */
public final class CredentialPreflight
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    /** 客户端列表理论上很短；上限只用于防止异常配置导致无限循环。 */
    private static final int MAX_CLIENTS = 32;

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        String problem = findProblem(context.getEnvironment());
        if (problem == null) {
            return;
        }
        printProblem(problem);
        System.err.flush();
        System.out.flush();
        System.exit(1);
    }

    /**
     * 返回第一条可读的凭证问题；没有问题时返回 {@code null}。
     *
     * <p>包级可见，便于单元测试，不触发 {@link System#exit(int)}。</p>
     */
    static String findProblem(ConfigurableEnvironment environment) {
        boolean anyEnabled = false;
        for (int i = 0; i < MAX_CLIENTS; i++) {
            String where = "app.security.clients[" + i + "]";
            String appKey = trimToNull(environment.getProperty(where + ".app-key"));
            String appSecret = trimToNull(environment.getProperty(where + ".app-secret"));
            String enabledValue = environment.getProperty(where + ".enabled");
            boolean declared = appKey != null || appSecret != null || enabledValue != null
                    || environment.containsProperty(where + ".app-key")
                    || environment.containsProperty(where + ".app-secret")
                    || environment.containsProperty(where + ".enabled");
            if (!declared) {
                break;
            }
            if (enabledValue != null && !Boolean.parseBoolean(enabledValue.trim())) {
                continue;
            }
            anyEnabled = true;
            if (appKey == null && appSecret == null) {
                return "缺少 --app-key 和 --app-secret（" + where + " 为空）";
            }
            if (appKey == null) {
                return "缺少 --app-key（" + where + ".app-key 为空）";
            }
            if (appSecret == null) {
                return "缺少 --app-secret（" + where + ".app-secret 为空）";
            }
        }
        if (!anyEnabled) {
            return "没有找到启用的客户端凭证（app.security.clients 为空或全部 enabled=false）";
        }
        return null;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void printProblem(String problem) {
        String line = "============================================================";
        System.err.println();
        System.err.println(line);
        System.err.println("ScopeFTP 启动失败：" + problem);
        System.err.println(line);
        System.err.println();
        System.err.println("请一次性提供两项（服务端没有内置默认凭证）：");
        System.err.println("  APP_SECRET=\"$(openssl rand -hex 32)\"");
        System.err.println("  java -jar ScopeFTP-server-*.jar --app-key demo-app --app-secret \"$APP_SECRET\"");
        System.err.println();
        System.err.println("AppKey 任意非空即可；AppSecret 任意非空字符串即可，不是只能填 32 字节。");
        System.err.println("服务端只对少于 32 字符的 AppSecret 提示，不会阻止启动；生产推荐随机密钥：");
        System.err.println("  openssl rand -hex 32");
        System.err.println();
        System.err.println("也可以使用环境变量：");
        System.err.println("  APP_SECRET=\"$(openssl rand -hex 32)\"");
        System.err.println("  UPLOAD_APP_KEY=demo-app UPLOAD_APP_SECRET=\"$APP_SECRET\" java -jar ScopeFTP-server-*.jar");
        System.err.println();
        System.err.println("修改凭证后重新执行启动命令即可；不要只传 --app-key。");
        System.err.println(line);
    }
}
