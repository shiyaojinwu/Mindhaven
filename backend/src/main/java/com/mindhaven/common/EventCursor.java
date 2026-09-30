package com.mindhaven.common;

import com.mindhaven.common.error.HttpProblem;
import java.math.BigInteger;

public final class EventCursor {
    private EventCursor() { }
    public static String validate(String value) {
        if (value == null || value.isBlank()) return "0";
        if (!value.matches("[0-9]{1,20}(-[0-9]{1,20})?")) throw new HttpProblem(400, "事件游标无效");
        return value;
    }
    public static int compare(String left, String right) {
        String[] a = left.split("-"), b = right.split("-");
        int first = new BigInteger(a[0]).compareTo(new BigInteger(b[0]));
        return first != 0 ? first : new BigInteger(a.length == 1 ? "0" : a[1])
                .compareTo(new BigInteger(b.length == 1 ? "0" : b[1]));
    }
}
