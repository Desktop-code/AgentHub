package com.agenthub.workflow.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * JSONLogic 安全子集求值器。所属域：workflow 的 domain。
 * <p>为什么不用 SpEL / 脚本引擎：租户可编辑的表达式一旦能反射或加载类就是 RCE 面。
 * 这里只实现白名单算子，未知算子直接报中文错误，宁可不支持也不放行。
 * <p>支持算子：{@code var missing in == != === !== > >= < <= and or ! !! cat + - * / % min max merge if}。
 * <p>复杂度防护：递归深度上限 {@link #MAX_DEPTH}，超限抛 {@link IllegalArgumentException}。
 * <p>线程安全：无状态静态方法；内部 {@link ObjectMapper} 只用其节点工厂/转换，线程安全。
 */
public final class JsonLogicEvaluator {

    /** 表达式最大嵌套深度；正常业务条件远小于该值 */
    public static final int MAX_DEPTH = 32;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private JsonLogicEvaluator() {
    }

    /**
     * 求值并判断真假。非布尔结果按 JSONLogic 真值口径：0 / "" / null / false / 空数组为假。
     *
     * @param rule 规则 JSON（可为 null，null 视为 false）
     * @param data 求值上下文（通常是 {input, outputs, vars}）
     * @return 真值
     * @throws IllegalArgumentException 未知算子 / 参数非法 / 深度超限
     */
    public static boolean test(JsonNode rule, JsonNode data) {
        return truthy(eval(rule, data, 0));
    }

    /**
     * 求值。
     *
     * @param rule  规则 / 字面量
     * @param data  上下文
     * @param depth 当前深度
     * @return 结果节点（可能为 null，等价于 NullNode）
     * @throws IllegalArgumentException 算子未知或参数类型不对
     */
    public static JsonNode eval(JsonNode rule, JsonNode data, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("条件表达式嵌套过深（上限 " + MAX_DEPTH + " 层）");
        }
        if (rule == null || rule.isNull()) {
            return NullNode.getInstance();
        }
        // 标量 / 数组：标量原样返回；数组按参数列表逐项求值
        if (rule.isNumber() || rule.isTextual() || rule.isBoolean()) {
            return rule;
        }
        if (rule.isArray()) {
            ArrayNode out = NODES.arrayNode();
            for (JsonNode item : rule) {
                out.add(eval(item, data, depth + 1));
            }
            return out;
        }
        if (!rule.isObject()) {
            return rule;
        }
        // 只接受「单键对象」这一种操作形式，避免多算子歧义
        if (rule.size() != 1) {
            throw new IllegalArgumentException("条件表达式每个对象只能有一个算子");
        }
        String op = rule.fieldNames().next();
        JsonNode rawArgs = rule.get(op);
        List<JsonNode> args = argList(rawArgs);
        return apply(op, args, data, depth);
    }

    private static JsonNode apply(String op, List<JsonNode> args, JsonNode data, int depth) {
        switch (op) {
            case "var":
                return evalVar(args, data, depth);
            case "missing":
                return missing(args, data, depth);
            case "==":
                return BooleanNode.valueOf(equalsLoose(value(args, 0, data, depth), value(args, 1, data, depth)));
            case "!=":
                return BooleanNode.valueOf(!equalsLoose(value(args, 0, data, depth), value(args, 1, data, depth)));
            case "===":
                return BooleanNode.valueOf(value(args, 0, data, depth)
                        .equals(value(args, 1, data, depth)));
            case "!==":
                return BooleanNode.valueOf(!value(args, 0, data, depth)
                        .equals(value(args, 1, data, depth)));
            case ">":
                return BooleanNode.valueOf(compare(args, data, depth) > 0);
            case ">=":
                return BooleanNode.valueOf(compare(args, data, depth) >= 0);
            case "<":
                return BooleanNode.valueOf(compare(args, data, depth) < 0);
            case "<=":
                return BooleanNode.valueOf(compare(args, data, depth) <= 0);
            case "and":
                for (JsonNode arg : args) {
                    JsonNode value = eval(arg, data, depth + 1);
                    if (!truthy(value)) {
                        return value;
                    }
                }
                return args.isEmpty() ? BooleanNode.TRUE : eval(args.get(args.size() - 1), data, depth + 1);
            case "or":
                for (JsonNode arg : args) {
                    JsonNode value = eval(arg, data, depth + 1);
                    if (truthy(value)) {
                        return value;
                    }
                }
                return args.isEmpty() ? BooleanNode.FALSE : eval(args.get(args.size() - 1), data, depth + 1);
            case "!":
                return BooleanNode.valueOf(!truthy(value(args, 0, data, depth)));
            case "!!":
                return BooleanNode.valueOf(truthy(value(args, 0, data, depth)));
            case "in":
                return BooleanNode.valueOf(in(value(args, 0, data, depth), value(args, 1, data, depth)));
            case "cat":
                return TextNode.valueOf(cat(args, data, depth));
            case "+":
                return NODES.numberNode(arithmetic(args, data, depth, '+'));
            case "-":
                return NODES.numberNode(arithmetic(args, data, depth, '-'));
            case "*":
                return NODES.numberNode(arithmetic(args, data, depth, '*'));
            case "/":
                return NODES.numberNode(arithmetic(args, data, depth, '/'));
            case "%":
                return NODES.numberNode(arithmetic(args, data, depth, '%'));
            case "min":
            case "max": {
                BigDecimal acc = null;
                for (JsonNode arg : args) {
                    BigDecimal next = decimal(eval(arg, data, depth + 1));
                    if (next == null) {
                        continue;
                    }
                    if (acc == null) {
                        acc = next;
                    } else {
                        acc = op.equals("min") ? acc.min(next) : acc.max(next);
                    }
                }
                return NODES.numberNode(acc == null ? BigDecimal.ZERO : acc);
            }
            case "merge":
                return merge(args, data, depth);
            case "if": {
                // if 支持 (cond, then) 与 (cond1, then1, cond2, then2, …, else)
                for (int i = 0; i + 1 < args.size(); i += 2) {
                    if (truthy(eval(args.get(i), data, depth + 1))) {
                        return eval(args.get(i + 1), data, depth + 1);
                    }
                }
                return args.size() % 2 == 1
                        ? eval(args.get(args.size() - 1), data, depth + 1)
                        : NullNode.getInstance();
            }
            default:
                throw new IllegalArgumentException("不支持的条件算子「" + op + "」");
        }
    }

    private static JsonNode evalVar(List<JsonNode> args, JsonNode data, int depth) {
        if (args.isEmpty()) {
            return data == null ? NullNode.getInstance() : data;
        }
        JsonNode pathNode = eval(args.get(0), data, depth + 1);
        String path = pathNode.isTextual() ? pathNode.asText() : pathNode.toString();
        JsonNode found = readPath(data, path);
        if (found != null && !found.isMissingNode()) {
            return found;
        }
        // 第二个参数是缺省值（可选）
        return args.size() > 1 ? eval(args.get(1), data, depth + 1) : NullNode.getInstance();
    }

    private static JsonNode missing(List<JsonNode> args, JsonNode data, int depth) {
        ArrayNode out = NODES.arrayNode();
        for (JsonNode arg : args) {
            JsonNode evaluated = eval(arg, data, depth + 1);
            if (evaluated.isArray()) {
                for (JsonNode item : evaluated) {
                    addIfMissing(out, data, item.asText());
                }
            } else {
                addIfMissing(out, data, evaluated.asText());
            }
        }
        return out;
    }

    private static void addIfMissing(ArrayNode out, JsonNode data, String path) {
        JsonNode found = readPath(data, path);
        if (found == null || found.isMissingNode() || found.isNull()) {
            out.add(path);
        }
    }

    /**
     * 读取 {@code a.b.0.c} 形式路径。空路径返回 data 本身。
     */
    private static JsonNode readPath(JsonNode data, String path) {
        if (data == null) {
            return null;
        }
        if (path == null || path.isBlank()) {
            return data;
        }
        String normalized = path.trim();
        if (normalized.startsWith("$.")) {
            normalized = normalized.substring(2);
        }
        JsonNode current = data;
        for (String part : normalized.split("\\.")) {
            if (part.isBlank()) {
                continue;
            }
            if (current == null || current.isNull()) {
                return null;
            }
            if (current.isArray()) {
                int index;
                try {
                    index = Integer.parseInt(part);
                } catch (NumberFormatException ex) {
                    return null;
                }
                current = current.size() > index ? current.get(index) : null;
            } else {
                current = current.get(part);
            }
        }
        return current;
    }

    private static JsonNode value(List<JsonNode> args, int index, JsonNode data, int depth) {
        if (index >= args.size()) {
            return NullNode.getInstance();
        }
        return eval(args.get(index), data, depth + 1);
    }

    private static int compare(List<JsonNode> args, JsonNode data, int depth) {
        JsonNode left = value(args, 0, data, depth);
        JsonNode right = value(args, 1, data, depth);
        BigDecimal l = decimal(left);
        BigDecimal r = decimal(right);
        if (l != null && r != null) {
            return l.compareTo(r);
        }
        return String.valueOf(textOrEmpty(left)).compareTo(String.valueOf(textOrEmpty(right)));
    }

    private static boolean equalsLoose(JsonNode left, JsonNode right) {
        BigDecimal l = decimal(left);
        BigDecimal r = decimal(right);
        if (l != null && r != null) {
            return l.compareTo(r) == 0;
        }
        if (left == null || left.isNull()) {
            return right == null || right.isNull();
        }
        return left.equals(right);
    }

    private static boolean in(JsonNode needle, JsonNode haystack) {
        if (haystack == null || haystack.isNull()) {
            return false;
        }
        if (haystack.isArray()) {
            for (JsonNode item : haystack) {
                if (equalsLoose(needle, item)) {
                    return true;
                }
            }
            return false;
        }
        if (haystack.isTextual()) {
            return haystack.asText().contains(textOrEmpty(needle));
        }
        return false;
    }

    private static String cat(List<JsonNode> args, JsonNode data, int depth) {
        StringBuilder builder = new StringBuilder();
        for (JsonNode arg : args) {
            builder.append(textOrEmpty(eval(arg, data, depth + 1)));
        }
        return builder.toString();
    }

    private static BigDecimal arithmetic(List<JsonNode> args, JsonNode data, int depth, char op) {
        if (args.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal acc = decimal(eval(args.get(0), data, depth + 1));
        if (acc == null) {
            acc = BigDecimal.ZERO;
        }
        // 一元减法取负；其余按左结合累算
        if (args.size() == 1 && op == '-') {
            return acc.negate();
        }
        for (int i = 1; i < args.size(); i++) {
            BigDecimal next = decimal(eval(args.get(i), data, depth + 1));
            if (next == null) {
                next = BigDecimal.ZERO;
            }
            switch (op) {
                case '+' -> acc = acc.add(next);
                case '-' -> acc = acc.subtract(next);
                case '*' -> acc = acc.multiply(next);
                case '/' -> {
                    if (next.compareTo(BigDecimal.ZERO) == 0) {
                        throw new IllegalArgumentException("条件表达式除以 0");
                    }
                    acc = acc.divide(next, 8, java.math.RoundingMode.HALF_UP);
                }
                case '%' -> {
                    if (next.compareTo(BigDecimal.ZERO) == 0) {
                        throw new IllegalArgumentException("条件表达式取模 0");
                    }
                    acc = acc.remainder(next);
                }
                default -> throw new IllegalArgumentException("未知算符 " + op);
            }
        }
        return acc;
    }

    private static JsonNode merge(List<JsonNode> args, JsonNode data, int depth) {
        ArrayNode out = NODES.arrayNode();
        for (JsonNode arg : args) {
            JsonNode value = eval(arg, data, depth + 1);
            if (value.isArray()) {
                value.forEach(out::add);
            } else if (!value.isNull()) {
                out.add(value);
            }
        }
        return out;
    }

    private static BigDecimal decimal(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isBoolean()) {
            return node.asBoolean() ? BigDecimal.ONE : BigDecimal.ZERO;
        }
        if (node.isTextual()) {
            try {
                return new BigDecimal(node.asText().trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    private static String textOrEmpty(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        return node.isTextual() ? node.asText() : node.toString();
    }

    /**
     * JSONLogic 真值口径：false / null / 0 / "" / 空数组为假。
     */
    private static boolean truthy(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return false;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isNumber()) {
            return node.decimalValue().compareTo(BigDecimal.ZERO) != 0;
        }
        if (node.isTextual()) {
            return !node.asText().isEmpty();
        }
        if (node.isArray()) {
            return node.size() > 0;
        }
        return true;
    }

    /**
     * JSONLogic 的参数既可以写成数组，也可以直接写单个值。
     */
    private static List<JsonNode> argList(JsonNode rawArgs) {
        List<JsonNode> args = new ArrayList<>();
        if (rawArgs == null || rawArgs.isNull()) {
            return args;
        }
        if (rawArgs.isArray()) {
            rawArgs.forEach(args::add);
        } else {
            args.add(rawArgs);
        }
        return args;
    }

    /**
     * 构造求值上下文：{@code {input, outputs, vars}}。
     *
     * @param input   试跑输入
     * @param outputs 已成功节点产出
     * @param vars    预留变量袋
     * @return 上下文对象；入参为 null 时给空对象
     */
    public static ObjectNode context(JsonNode input, ObjectNode outputs, ObjectNode vars) {
        ObjectNode ctx = JSON.createObjectNode();
        ctx.set("input", input == null ? JSON.createObjectNode() : input);
        ctx.set("outputs", outputs == null ? JSON.createObjectNode() : outputs);
        ctx.set("vars", vars == null ? JSON.createObjectNode() : vars);
        return ctx;
    }
}
