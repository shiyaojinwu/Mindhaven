package com.mindhaven.service.agent;

import java.util.regex.Pattern;

/** Business checks after a normal model stop; not a callable tool. */
public final class AgentCompletion {
    // Match only a whole short holding response, never a phrase embedded in an answer.
    private static final Pattern PROMISE = Pattern.compile(
            "^(?:好的[，,。！!\\s]*)?(?:请)?(?:稍等(?:一下)?|等一下)(?:[，,]?(?:我(?:来|帮你|去)?(?:查找|查询|找找|看看)(?:一下)?))?[。！!～~\\s]*$"
            + "|^(?:好的[，,。！!\\s]*)?我(?:来|帮你|去)(?:查找|查询|找找)(?:一下)?[。！!～~\\s]*$");
    private AgentCompletion() { }
    public static String answer(String answer) {
        if (answer == null || answer.isBlank() || answer.codePoints().noneMatch(Character::isLetterOrDigit)) throw new IllegalArgumentException("模型未返回有效正文，请给出回答或调用工具");
        if (PROMISE.matcher(answer.strip()).matches()) throw new IllegalArgumentException("不能以查找承诺结束，请执行查询或明确说明限制");
        return answer;
    }
}
