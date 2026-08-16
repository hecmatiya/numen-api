package com.dwinovo.numen.agent.prompt;

import com.dwinovo.numen.entity.NumenPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * 世界状态注入器的登记处:内容包(numen-core、第三方)把各自的
 * {@link WorldContextProvider} 注册进来,主人每次说话时按注册顺序拼接,
 * 包在 {@code <world_state>…</world_state>} 里插到用户消息前面。
 *
 * <p>Init-time only(与 ToolRegistry 同一约定):注册发生在 mod init,
 * 注入发生在服务器 tick 的对话回合——无并发窗口。
 */
public final class WorldContextRegistry {

    private static final List<WorldContextProvider> PROVIDERS = new ArrayList<>();

    private WorldContextRegistry() {}

    /** 注册一个上下文注入器。 */
    public static synchronized void register(WorldContextProvider provider) {
        PROVIDERS.add(provider);
    }

    /** 已注册的注入器数量(日志用)。 */
    public static synchronized int size() {
        return PROVIDERS.size();
    }

    /**
     * 把主人的话包装成带世界状态的用户回合:
     * {@code <world_state>…</world_state>\n<query>…</query>}
     * 状态为空或不解析不到身体(联网服务器)时不包 <world_state>(省 token)。
     */
    public static synchronized String wrap(String queryText, java.util.UUID entityUuid) {
        // 客户端大脑循环:单机集成服务器能解析到服务端身体;联网服务器拿不到 → 降级。
        NumenPlayer companion = null;
        try {
            var server = net.minecraft.client.Minecraft.getInstance().getSingleplayerServer();
            if (server != null) {
                companion = NumenPlayer.findByUuid(server, entityUuid);
            }
        } catch (RuntimeException ignored) {
            // 不是客户端环境(测试等):不注入。
        }
        StringBuilder ctx = new StringBuilder();
        for (WorldContextProvider p : PROVIDERS) {
            try {
                String part = companion == null ? "" : p.contextFor(companion);
                if (part != null && !part.isBlank()) {
                    if (ctx.length() > 0) {
                        ctx.append('\n');
                    }
                    ctx.append(part);
                }
            } catch (RuntimeException e) {
                com.dwinovo.numen.Constants.LOG.warn(
                        "[numen-world-context] provider {} 抛异常,本次跳过: {}",
                        p.id(), e.toString());
            }
        }
        if (ctx.length() == 0) {
            return "<query>" + queryText + "</query>";
        }
        return "<world_state>\n" + ctx + "\n</world_state>\n<query>" + queryText + "</query>";
    }
}
