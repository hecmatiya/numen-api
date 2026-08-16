package com.dwinovo.numen.task;

import com.dwinovo.numen.task.CompanionTickDispatcher;
import com.dwinovo.numen.task.TaskRecord;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Control tool (instant): abort the background task so the body frees up. */
public final class TaskStopTool implements NumenTool {

    private static final Gson GSON = new Gson();

    private record Args(String task_id) {}

    @Override
    public String name() {
        return "task_stop";
    }

    @Override
    public String description() {
        return "Abort the background task (the one <current_task> / task_status shows) so the body "
                + "frees up for something else, or cancel a timer you set with set_timer (pass its id, "
                + "e.g. tm12 — task_status lists them). The wind-down summary arrives as a "
                + "task_finished event with status=stopped. Fails when nothing matches.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalString("task_id", "Optional safety check: the id you mean to stop "
                        + "(e.g. t42 for a task, or tm12 for a timer). "
                        + "If it doesn't match anything running, nothing is stopped.")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        String wanted = a == null ? null : a.task_id();
        MinecraftServer server = companion.level().getServer();
        long now = companion.level().getGameTime();

        // 指名道姓的表:先在表里找,找到就撤。
        if (wanted != null && !wanted.isBlank() && server != null) {
            TimerRegistry registry = TimerRegistry.get(server);
            if (registry.cancel(companion.getUUID(), wanted)) {
                reply.accept(TaskResult.ok("已撤掉表 " + wanted + "。",
                        Map.of("timer_id", wanted)).toJson());
                return;
            }
        }

        TaskRecord active = CompanionTickDispatcher.asyncTaskFor(companion.getUUID());
        if (active == null || (wanted != null && !wanted.isBlank()
                && !wanted.equals(active.publicId()))) {
            reply.accept(TaskResult.fail(nothingMatched(wanted, active, server, companion, now)).toJson());
            return;
        }
        CompanionTickDispatcher.stopActive(companion, "stopped by task_stop");
        reply.accept(TaskResult.ok("已叫停 " + active.publicId() + "(" + active.describe()
                + ")。收尾结果会以 task_finished(status=stopped) 事件送达。",
                Map.of("task_id", active.publicId())).toJson());
    }

    /** 没撤成的时候把现状摊开:身体在干嘛、挂着哪些表。 */
    private static String nothingMatched(String wanted, TaskRecord active, MinecraftServer server,
                                         NumenPlayer companion, long now) {
        List<TimerRegistry.Timer> timers = server == null
                ? List.of()
                : TimerRegistry.get(server).list(companion.getUUID());
        String body = active == null
                ? "身体空闲"
                : "身体在跑 " + active.publicId() + "(" + active.describe() + ")";
        String pending = body + ";表:" + SetTimerTool.summarize(timers, now);
        return wanted == null || wanted.isBlank()
                ? "没有进行中的后台任务,不需要叫停。当前:" + pending + "。"
                : "没有 " + wanted + " 这个 id。当前:" + pending + "。";
    }
}
