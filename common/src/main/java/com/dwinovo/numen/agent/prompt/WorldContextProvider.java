package com.dwinovo.numen.agent.prompt;

import com.dwinovo.numen.entity.NumenPlayer;

/**
 * 世界状态注入器——车万女仆式"人类会自然而然注意到周围环境"的移植:
 * 每次主人说话,把女仆身边的环境状态(自身/主人/附近实体)拼进用户回合,
 * 模型不用先调感知工具就"看得到"周围。
 *
 * <p>内容必须<b>轻量</b>:现取、简短、每轮都注入——它是提醒,不是调查
 * (想深查还是用感知工具)。多个 provider 按注册顺序拼接。
 */
public interface WorldContextProvider {

    /** 稳定 id(日志/调试用)。 */
    String id();

    /** 一句话说明注入了什么(注册表概览用)。 */
    String describe();

    /** 生成一段上下文文本;不需要注入时返回空串。 */
    String contextFor(NumenPlayer companion);
}
