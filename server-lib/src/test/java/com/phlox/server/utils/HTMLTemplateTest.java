package com.phlox.server.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HTMLTemplateTest {

    public static class FileModel {
        public String path;
        public String name;
        public boolean isFolder;

        FileModel(String path, String name, boolean isFolder) {
            this.path = path;
            this.name = name;
            this.isFolder = isFolder;
        }
    }

    private static String process(String template, Object data) {
        return new HTMLTemplate(template).process(data);
    }

    @Test
    public void escapesMarkupInTextContent() {
        Map<String, Object> data = new HashMap<>();
        data.put("name", "<img src=x onerror=alert(1)>");

        String result = process("<div>{{name}}</div>", data);

        assertEquals("<div>&lt;img src=x onerror=alert(1)&gt;</div>", result);
        assertFalse(result.contains("<img"));
    }

    @Test
    public void escapesQuotesSoAttributeCanNotBeBrokenOut() {
        Map<String, Object> data = new HashMap<>();
        data.put("path", "/dir/evil\" onmouseover=\"alert(1)");

        String result = process("<a href=\"{{path}}\">link</a>", data);

        assertEquals("<a href=\"/dir/evil&quot; onmouseover=&quot;alert(1)\">link</a>", result);
    }

    @Test
    public void escapesSingleQuote() {
        Map<String, Object> data = new HashMap<>();
        data.put("name", "it's");

        assertEquals("<div class='it&#39;s'></div>", process("<div class='{{name}}'></div>", data));
    }

    @Test
    public void doesNotDoubleEscapeAmpersand() {
        Map<String, Object> data = new HashMap<>();
        data.put("name", "rock & <roll>");

        assertEquals("<div>rock &amp; &lt;roll&gt;</div>", process("<div>{{name}}</div>", data));
    }

    @Test
    public void leavesTemplateMarkupItselfUntouched() {
        Map<String, Object> data = new HashMap<>();
        data.put("name", "plain");

        assertEquals("<div class=\"a&b\">plain</div>",
                process("<div class=\"a&b\">{{name}}</div>", data));
    }

    @Test
    public void escapesValuesInsideIteratedSections() {
        List<FileModel> files = new ArrayList<>();
        files.add(new FileModel("/a\"b", "a\"b", false));
        files.add(new FileModel("/sub", "<script>", true));
        Map<String, Object> data = new HashMap<>();
        data.put("list", files);

        String result = process("{{#list}}<a href=\"{{path}}\">" +
                "<div class=\"{{#isFolder}}folder{{/isFolder}}\">{{name}}</div></a>{{/list}}", data);

        assertEquals("<a href=\"/a&quot;b\"><div class=\"\">a&quot;b</div></a>" +
                "<a href=\"/sub\"><div class=\"folder\">&lt;script&gt;</div></a>", result);
    }

    @Test
    public void rendersBooleanSectionsAndIterationVariables() {
        List<String> items = new ArrayList<>();
        items.add("one");
        items.add("two");
        Map<String, Object> data = new HashMap<>();
        data.put("list", items);
        data.put("visible", true);
        data.put("hidden", false);

        String result = process("{{#visible}}yes{{/visible}}{{#hidden}}no{{/hidden}}" +
                "{{^hidden}}fallback{{/hidden}}" +
                "{{#list}}[{{@list}}:{{.}}:{{@last}}]{{/list}}", data);

        assertEquals("yesfallback[1:one:false][2:two:true]", result);
    }

    @Test
    public void missingValueRendersNothing() {
        assertEquals("<div></div>", process("<div>{{absent}}</div>", new HashMap<String, Object>()));
    }
}
