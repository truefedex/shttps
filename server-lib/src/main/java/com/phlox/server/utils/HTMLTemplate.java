package com.phlox.server.utils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HTMLTemplate {
    public final String template;
    public final List<Object> datas = new ArrayList<>();
    public final Map<String, Object> variables = new HashMap<>();
    private final Matcher sectionMatcher;
    private final Matcher singleMatcher;

    public HTMLTemplate(String template) {
        this.template = template;
        Pattern sectionPattern = Pattern.compile("\\{\\{([#^])([\\w\\.@]+)\\}\\}");
        sectionMatcher = sectionPattern.matcher(template);
        Pattern singlePattern = Pattern.compile("\\{\\{\\s*([\\w\\.@]+)\\s*\\}\\}");
        singleMatcher = singlePattern.matcher(template);
    }

    public String process(Object data) {
        datas.add(data);
        StringBuilder stringBuffer = new StringBuilder();
        parse(stringBuffer, 0, template.length() - 1);
        datas.clear();
        variables.clear();
        return stringBuffer.toString();
    }

    @SuppressWarnings("rawtypes")
    private void parse(StringBuilder stringBuffer, int start, int end) {
        int cursor = start;
        while (cursor < end) {
            boolean sectionFound = sectionMatcher.find(cursor);
            int pairPos = -1;
            if (sectionFound) {
                pairPos = sectionMatcher.start();
            }
            boolean singleFound = singleMatcher.find(cursor);
            int singlePos = -1;
            if (singleFound) {
                singlePos = singleMatcher.start();
            }
            if (pairPos >= end) pairPos = -1;
            if (singlePos >= end) singlePos = -1;
            if (pairPos != -1 && (singlePos == -1 || pairPos < singlePos)) {
                if (cursor < pairPos) {
                    stringBuffer.append(template, cursor, pairPos);
                }

                String name = sectionMatcher.group(2);
                int contentStartPos = sectionMatcher.end();
                char operationSymbol = Objects.requireNonNull(sectionMatcher.group(1)).charAt(0);
                int closingTagPos = findEnclosingTag(contentStartPos, name);
                int contentEndPos = closingTagPos - 1;
                String closingTag = "{{/" + name + "}}";

                Object field = findData(name);
                if ((field instanceof Iterable)) {
                    Iterable iterableField = (Iterable) field;
                    int i = 0;
                    String iterationPositionVarName = "@" + name;
                    String lastIterationVarName = "@last";
                    Iterator iter = iterableField.iterator();
                    while (iter.hasNext()) {
                        Object obj = iter.next();
                        variables.put(iterationPositionVarName, String.valueOf(i + 1));
                        variables.put(lastIterationVarName, !iter.hasNext());
                        datas.add(obj);
                        parse(stringBuffer, contentStartPos, contentEndPos);
                        datas.remove(obj);
                        i++;
                    }
                    variables.remove(iterationPositionVarName);
                    variables.remove(lastIterationVarName);
                } else {
                    boolean booleanValue = false;
                    if (field != null && Boolean.class.isAssignableFrom(field.getClass())) {
                        booleanValue = (Boolean) field;
                    }
                    if ((operationSymbol == '#' && booleanValue) ||
                            (operationSymbol == '^' && !booleanValue)) {
                        parse(stringBuffer, contentStartPos, contentEndPos);
                    }
                }

                cursor = closingTagPos + closingTag.length();
            } else if (singlePos != -1) {
                String name = singleMatcher.group(1);
                if (cursor < singlePos) {
                    stringBuffer.append(template, cursor, singlePos);
                }
                Object field = findData(name);
                if (field != null) {
                    stringBuffer.append(field.toString());
                }
                cursor = singleMatcher.end();
            } else {
                break;
            }
        }
        if (cursor <= end) {
            stringBuffer.append(template, cursor, end + 1);
        }
    }

    @SuppressWarnings("deprecation")
    private Object findData(String name) {
        Object variable = variables.get(name);
        if (variable != null) {
            return variable;
        }
        for (int i = datas.size() -1; i >= 0; i--) {
            Object data = datas.get(i);
            if (".".equals(name)) {
                return data;
            } else if (data instanceof Map<?, ?>) {
                Object value = ((Map<?, ?>) data).get(name);
                if (value != null) {
                    return value;
                }
            } else {
                Class<?> dataClass = data.getClass();
                try {
                    Field field = dataClass.getDeclaredField(name);
                    if (!field.isAccessible()) {
                        field.setAccessible(true);
                    }
                    return field.get(data);
                } catch (Exception e) {
                    //it is normal
                }
                try {
                    Method method = dataClass.getMethod(name);
                    return method.invoke(data);
                } catch (Exception e) {
                    //it is normal
                }
            }
        }
        return null;
    }

    private int findEnclosingTag(int startPos, String name) {
        int originalStartPos = startPos;
        int endPos;
        int openPos;
        boolean subSectionFound;
        String enclosingTag = "{{/" + name + "}}";
        do {
            endPos = template.indexOf(enclosingTag, startPos);
            subSectionFound = sectionMatcher.find(startPos);
            openPos = -1;
            if (subSectionFound) {
                openPos = sectionMatcher.start();
            }
            if (subSectionFound && openPos < endPos) {
                //some another child tag found
                String childName = sectionMatcher.group(2);
                String childEnclosingTag = "{{/" + childName + "}}";
                startPos = findEnclosingTag(sectionMatcher.end(), childName) + childEnclosingTag.length();
            }
        } while (subSectionFound && openPos < endPos);
        if (endPos == -1) {
            throw new IllegalStateException("Can not find enclosing tag for: " + "{{#" + name + " at " + formatErrorPosition(originalStartPos));
        }
        return endPos;
    }

    private String formatErrorPosition(int templatePosition) {
        int line = 0;
        int linePosition = 0;
        int pos = 0;
        do {
            int newLine = template.indexOf("\n", pos);
            if (newLine == -1 || newLine > templatePosition) {
                linePosition = templatePosition - pos;
                break;
            } else {
                pos = newLine;
                line++;
            }
        } while (true);
        return "line: " + line + ", pos:" + linePosition;
    }
}
