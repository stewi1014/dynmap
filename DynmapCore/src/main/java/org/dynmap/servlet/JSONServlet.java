package org.dynmap.servlet;

import org.json.simple.JSONStreamAware;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

public class JSONServlet {
    public static void respond(HttpServletResponse response, JSONStreamAware json) throws IOException {
        response.setContentType("application/json");
        PrintWriter writer = response.getWriter();
        json.writeJSONString(writer);
        writer.close();
    }
}
