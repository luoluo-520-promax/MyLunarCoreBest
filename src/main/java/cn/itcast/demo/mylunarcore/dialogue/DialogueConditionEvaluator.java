package cn.itcast.demo.mylunarcore.dialogue;

import java.util.Locale;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.function.ToIntFunction;

/**
 * 对话分支条件求值器。
 * <p>
 * 业务含义：AVG 对话中，某些分支不是单纯“点了就跳”，而是要依据玩家状态决定是否可选，
 * 例如：是否持有某个剧情 flag、是否拥有某道具、玩家等级是否达到要求、某个变量是否满足阈值。
 * 本类提供一个轻量表达式解析器，支持：
 * <ul>
 *   <li>逻辑与/或/非：&&、||、!</li>
 *   <li>flag:xxx：判断剧情标记是否存在</li>
 *   <li>item:1001：判断是否拥有道具 ID=1001</li>
 *   <li>level:&gt;=10：判断玩家等级</li>
 *   <li>var:name&gt;=3：读取外部变量值并比较</li>
 * </ul>
 * 该实现刻意保持轻量，以便在剧情推进的高频路径中低成本执行。
 */
public final class DialogueConditionEvaluator {

    /**
     * 计算上下文：承载判断表达式所需要的最小玩家状态。
     *
     * @param flags    已持有的剧情 flag 集合
     * @param playerLevel 玩家等级
     * @param hasItem   判断是否拥有指定道具的谓词
     * @param varValue  按名称读取变量值的函数（如任务进度、亲密度等）
     */
    public record EvalContext(Set<String> flags, int playerLevel, IntPredicate hasItem,
                              ToIntFunction<String> varValue) {
        /** 归一化上下文：避免传入 null，保证求值器稳定运行。 */
        public EvalContext {
            flags = flags == null ? Set.of() : Set.copyOf(flags);
            hasItem = hasItem == null ? id -> false : hasItem;
            varValue = varValue == null ? k -> 0 : varValue;
        }

        /** 仅以 flag 集合构造上下文（适用于只做剧情标记判断的分支）。 */
        public static EvalContext ofFlags(Set<String> flags) {
            return new EvalContext(flags, 0, id -> false, k -> 0);
        }
    }

    private DialogueConditionEvaluator() {
    }

    /**
     * 对外入口：判断一段条件表达式是否成立。
     * 空表达式视为永真，便于配置中省略条件。
     */
    public static boolean evaluate(String expression, EvalContext ctx) {
        if (expression == null || expression.isBlank()) {
            return true;
        }
        return evalOr(expression.trim(), ctx);
    }

    /** 解析逻辑“或”（||）。 */
    private static boolean evalOr(String expr, EvalContext ctx) {
        String[] parts = splitTop(expr, "||");
        if (parts.length == 1) {
            return evalAnd(parts[0].trim(), ctx);
        }
        for (String p : parts) {
            if (evalAnd(p.trim(), ctx)) {
                return true;
            }
        }
        return false;
    }

    /** 解析逻辑“与”（&&）。 */
    private static boolean evalAnd(String expr, EvalContext ctx) {
        String[] parts = splitTop(expr, "&&");
        for (String p : parts) {
            if (!evalAtom(p.trim(), ctx)) {
                return false;
            }
        }
        return true;
    }

    /** 解析原子条件：支持 flag/item/level/var/裸 flag 以及前缀 ! 取反。 */
    private static boolean evalAtom(String atom, EvalContext ctx) {
        if (atom.isEmpty()) {
            return true;
        }
        if (atom.startsWith("!")) {
            return !evalAtom(atom.substring(1).trim(), ctx);
        }
        String lower = atom.toLowerCase(Locale.ROOT);
        if (lower.startsWith("flag:")) {
            return ctx.flags().contains(atom.substring(5).trim());
        }
        if (lower.startsWith("item:")) {
            try {
                int itemId = Integer.parseInt(atom.substring(5).trim());
                return ctx.hasItem().test(itemId);
            } catch (NumberFormatException e) {
                return false;
            }
        }
        if (lower.startsWith("level:")) {
            return compareInt(ctx.playerLevel(), atom.substring(6).trim());
        }
        if (lower.startsWith("var:")) {
            // var:name>=3 或 var:name
            String rest = atom.substring(4).trim();
            int opIdx = indexOfOp(rest);
            if (opIdx < 0) {
                return ctx.varValue().applyAsInt(rest) > 0;
            }
            String name = rest.substring(0, opIdx).trim();
            String cmp = rest.substring(opIdx).trim();
            return compareInt(ctx.varValue().applyAsInt(name), cmp);
        }
        // 裸 flag 名兼容 requireFlag
        return ctx.flags().contains(atom);
    }

    /** 比较整数：支持 >=, <=, >, <, =, ==；未识别比较符时按“>=”理解。 */
    private static boolean compareInt(int left, String cmp) {
        if (cmp.startsWith(">=")) {
            return left >= parseRight(cmp.substring(2));
        }
        if (cmp.startsWith("<=")) {
            return left <= parseRight(cmp.substring(2));
        }
        if (cmp.startsWith(">")) {
            return left > parseRight(cmp.substring(1));
        }
        if (cmp.startsWith("<")) {
            return left < parseRight(cmp.substring(1));
        }
        if (cmp.startsWith("==") || cmp.startsWith("=")) {
            String r = cmp.startsWith("==") ? cmp.substring(2) : cmp.substring(1);
            return left == parseRight(r);
        }
        return left >= parseRight(cmp);
    }

    /**
     * 解析右侧比较值；非法数字返回 Integer.MAX_VALUE，避免条件误判为通过。
     */
    private static int parseRight(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * 找到 var 表达式里的比较运算符位置。
     * 返回值 &gt; 0 表示找到；用于拆分变量名和比较段。
     */
    private static int indexOfOp(String s) {
        for (String op : new String[]{">=", "<=", "==", ">", "<", "="}) {
            int i = s.indexOf(op);
            if (i > 0) {
                return i;
            }
        }
        return -1;
    }

    /** 简化版分割：无括号嵌套时按运算符切分，适合当前剧情配置复杂度。 */
    private static String[] splitTop(String expr, String sep) {
        return expr.split(java.util.regex.Pattern.quote(sep));
    }
}
