package com.dwinovo.numen.client.chat;

import com.dwinovo.numen.api.NumenGateway;
import com.dwinovo.numen.client.agent.NumenRoster;
import com.dwinovo.numen.client.screen.FlatEditBox;
import com.dwinovo.numen.client.screen.Nb;
import com.dwinovo.numen.client.screen.UiTheme;
import com.dwinovo.numen.client.ui.RoundRect;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.EntityHitResult;

import org.lwjgl.glfw.GLFW;

import java.util.UUID;

/**
 * 快捷对话:按对话键(默认 Y,入口在 {@code NumenKeys})对「当前交互
 * 对象」弹出这一条极简输入框——就一行字,回车说出去立刻关屏,回复会浮
 * 在它头顶的气泡里。收件人由 {@code SelectedCompanion} 解析:准星指着
 * 谁优先谁,否则是轮盘选中的那位。屏只是输入法,不是对话窗口;历史与
 * 长文在 G 面板。准星提示见 {@code TalkHint}。
 *
 * <p>与 {@code @名字} 路由是同一条管线({@link NumenGateway#enqueue}),
 * 对应两种社交距离:@ 是远程喊话,这里是走到跟前说话。
 */
public class CompanionChatScreen extends Screen {

    /** DSH 桥接专用日志器（latest.log 里搜 [ingame-bridge]）。 */
    private static final org.slf4j.Logger DSH_BRIDGE_LOG =
            org.slf4j.LoggerFactory.getLogger("NumenIngameBridge");

    private static final int INPUT_W = 300;
    private static final int INPUT_H = 14;

    private final UUID companionUuid;
    private final String companionName;
    private FlatEditBox input;

    public CompanionChatScreen(UUID companionUuid, String companionName) {
        super(Component.literal("Numen face-to-face chat"));
        this.companionUuid = companionUuid;
        this.companionName = companionName == null ? "?" : companionName;
    }

    /**
     * 准星此刻指着的「自己的」同伴,不在指着返回 null。花名册只含本人的
     * 同伴,身份校验是白送的;别人的同伴不响应(它的大脑不在你机器上)。
     */
    public static AbstractClientPlayer crosshairCompanion() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.isSpectator()) return null;
        if (!(mc.hitResult instanceof EntityHitResult hit)) return null;
        if (!(hit.getEntity() instanceof AbstractClientPlayer body)) return null;
        for (NumenRoster.Entry entry : NumenRoster.instance().entries()) {
            if (entry.uuid().equals(body.getUUID())) {
                return body;
            }
        }
        return null;
    }

    @Override
    protected void init() {
        String kept = input != null ? input.getValue() : "";
        int x = (this.width - INPUT_W) / 2;
        int y = this.height - 44;
        // G 面板同款 FlatEditBox:深色字、无阴影、占位提示画在光标下层——
        // 原版 EditBox 的阴影是写死的,浅底上深色字会糊成一团
        input = new FlatEditBox(this.font, x, y, INPUT_W, INPUT_H,
                Component.literal("numen chat input"));
        input.setBordered(false);
        input.setTextColor(UiTheme.current().text());
        input.setHint(Nb.colored("想说什么…(回车说出去,Esc 算了)", UiTheme.current().textDim()));
        input.setMaxLength(256);
        input.setValue(kept);
        input.setCanLoseFocus(false);
        addWidget(input);
        setInitialFocus(input);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTicks) {
        // 刻意留空:super.extractRenderState 默认会画菜单模糊+压暗遮罩,面对面说话
        // 不该把世界糊掉——她就站在你面前
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTicks) {
        UiTheme th = UiTheme.current();
        int x = input.getX();
        int y = input.getY();

        // 名字牌:输入框左上角一枚小卡,标明这句话说给谁
        String tag = companionName;
        int tagW = this.font.width(tag) + 12;
        RoundRect.card(g, x - 6, y - 24, x - 6 + tagW, y - 9, 3, th.band(), th.border());
        Nb.text(g, this.font, tag, x, y - 20, th.onBand());

        // 输入卡:与 G 面板同方言的浅底粗边卡片
        RoundRect.card(g, x - 8, y - 6, x + INPUT_W + 8, y + INPUT_H + 4, 4,
                th.aiFill(), th.border());
        input.extractRenderState(g, mouseX, mouseY, partialTicks);

        super.extractRenderState(g, mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int keyCode = event.key();
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            send();
            return true;
        }
        return super.keyPressed(event);
    }

    private void send() {
        String text = input.getValue().trim();
        if (!text.isEmpty()) {
            boolean queued = com.dwinovo.numen.client.agent.AgentLoopRegistry.get(companionUuid)
                    .map(l -> l.isBusy() || l.hasQueuedPrompts()).orElse(false);
            boolean accepted = NumenGateway.enqueue(companionUuid, text);
            if (accepted) {
                ChatLines.owner(companionName, text, false, queued);
            } else {
                com.dwinovo.numen.client.hud.TalkHint.flash(
                        companionName + " 没能收到——它可能不在线", 3000);
            }
            // DSH 桥接：把 Y 键对话内容同时直送 DeepSeek Harness（http://127.0.0.1:3080/ingame）
            sendToDsh(text);
        }
        onClose();
    }

    /** 异步把对话内容 POST 到 DSH 的 /ingame 端点，失败静默，不影响游戏。 */
    private void sendToDsh(String text) {
        Thread t = new Thread(() -> {
            try {
                // 用 java.base 自带的 HttpURLConnection（mod 环境对 java.net.http 模块不可见）
                String json = "{\"companion\":\"" + jsonEscape(companionName)
                        + "\",\"text\":\"" + jsonEscape(text) + "\"}";
                java.net.URL url = new java.net.URL("http://127.0.0.1:3080/ingame");
                DSH_BRIDGE_LOG.info("[ingame-bridge] POST url={} proxy={} json={}",
                        url, System.getProperty("http.proxyHost", "-"), json);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(5000);
                conn.setDoOutput(true);
                byte[] payload = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                try (java.io.OutputStream os = conn.getOutputStream()) {
                    os.write(payload);
                }
                int code = conn.getResponseCode();
                String resp = readResponseBody(conn, code);
                conn.disconnect();
                DSH_BRIDGE_LOG.info("[ingame-bridge] -> dsh code={} resp={}", code, resp);
            } catch (Exception e) {
                DSH_BRIDGE_LOG.error("[ingame-bridge] -> dsh failed", e);
            }
        });
        t.setDaemon(true);
        t.start();
    }

    /** 读取响应体（4xx/5xx 走 errorStream），便于日志定位。 */
    private static String readResponseBody(java.net.HttpURLConnection conn, int code) {
        try {
            java.io.InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (is == null) return "(no body)";
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                return sb.length() == 0 ? "(empty)" : sb.toString();
            }
        } catch (Exception e) {
            return "(read err: " + e.getMessage() + ")";
        }
    }

    /** 最小 JSON 字符串转义（引号/反斜杠/控制字符）。 */
    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
