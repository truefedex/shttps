package com.phlox.server.handlers;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.request.RequestParser;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.Arrays;
import java.util.Comparator;

public class FolderContentRenderingRequestHandler extends StaticFileRequestHandler {
    public FolderContentRenderingRequestHandler(File root) {
        super(root);
    }

    @Override
    public Response handleRequest(RequestContext context, Request request, RequestParser requestParser) throws Exception {
        if (!request.method.equals(Request.METHOD_GET)) {
            return StandardResponses.METHOD_NOT_ALLOWED(new String[]{Request.METHOD_GET});
        }
        String destPath = root.getAbsolutePath() + request.path;
        File file = new File(destPath);
        if (file.isDirectory()) {
            File index = new File(file, "index.html");
            if (index.exists()) {
                return super.handleRequest(context, request, requestParser);
            } else {
                return renderDirContentPage(request, file);
            }
        }

        return super.handleRequest(context, request, requestParser);
    }

    private String getPathRelativeToRoot(File file) {
        return "/" + root.toURI().relativize(file.toURI()).getRawPath();
    }

    private Response renderDirContentPage(Request request, File folder) {
        File[] content = folder.listFiles();
        StringBuilder htmlList = new StringBuilder();
        if (!folder.equals(root)) {
            htmlList.append("<a href=\"");
            htmlList.append(getPathRelativeToRoot(folder.getParentFile()));
            htmlList.append("\"><li class=\"item float-item folder\">..</li></a>");
        }
        if (content != null) {
            Arrays.sort(content, new Comparator<File>() {
                @Override
                public int compare(File f1, File f2) {
                    int result = Boolean.compare(f2.isDirectory(), f1.isDirectory());
                    if (result != 0) {
                        return result;
                    }
                    return f1.getName().compareToIgnoreCase(f2.getName());
                }
            });
            for (File f : content) {
                htmlList.append("<a href=\"");
                htmlList.append(getPathRelativeToRoot(f));
                htmlList.append("\"><li class=\"item float-item ");
                if (f.isDirectory()) {
                    htmlList.append("folder");
                }
                htmlList.append("\">");
                htmlList.append(f.getName()).append("</li></a>");
            }
        }
        String html = String.format(HTML_TEMPLATE, request.path, htmlList.toString());
        return new Response("text/html", html.length(), new ByteArrayInputStream(html.getBytes()));
    }

    private static final String HTML_TEMPLATE = "<!DOCTYPE html>\n" +
            "<html>\n" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n" +
            "<meta charset=\"UTF-8\">\n" +
            "<style>\n" +
            "body {\n" +
            "  background: #303030;\n" +
            "  color: #C1C1C1;\n" +
            "  font-family: serif;\n" +
            "  padding: 0px 10px 0px;\n" +
            "}\n" +
            ".container {\n" +
            "  list-style:none;\n" +
            "  margin: 0;\n" +
            "  padding: 0;\n" +
            "}\n" +
            ".folder {\n" +
            "  background: #306477;\n" +
            "  text-decoration: none;\n" +
            "}\n" +
            ".item {\n" +
            "  padding: 5px;\n" +
            "  width: 300px;\n" +
            "  height: 50px;\n" +
            "  margin: 10px;\n" +
            "  \n" +
            "  line-height: 50px;\n" +
            "  font-size: 1.5em;\n" +
            "  text-align: start;\n" +
            "  white-space: nowrap;\n" +
            "  overflow: hidden;\n" +
            "  text-overflow: ellipsis;\n" +
            "}\n" +
            "\n" +
            "/*float layout*/\n" +
            ".float {\n" +
            "  margin: 0 auto;\n" +
            "}\n" +
            ".float:after {\n" +
            "  content: \".\";\n" +
            "  display: block;\n" +
            "  height: 0;\n" +
            "  clear: both;\n" +
            "  visibility: hidden;\n" +
            "}\n" +
            ".float-item {\n" +
            "  float: left;\n" +
            "}\n" +
            "a:link {\n" +
            "  color: white;\n" +
            "  background-color: transparent;\n" +
            "}\n" +
            "\n" +
            "a:visited {\n" +
            "  color: #aaaaaa;\n" +
            "  background-color: transparent;\n" +
            "}\n" +
            "</style>\n" +
            "<body>\n" +
            "\n" +
            "  <h1>%s</h1>\n" +
            "  <ul class=\"container float\">\n" +
            "%s" +
            "  </ul>\n" +
            "\n" +
            "</body>\n" +
            "</html>\n";
}
