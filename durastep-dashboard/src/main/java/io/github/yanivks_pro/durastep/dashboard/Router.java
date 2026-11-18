package io.github.yanivks_pro.durastep.dashboard;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.github.yanivks_pro.durastep.api.DurastepClient;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class Router implements HttpHandler {

  private static final Logger log = LoggerFactory.getLogger(Router.class);
  private static final String HTML = "text/html; charset=utf-8";
  private static final String CSS = "text/css; charset=utf-8";
  private static final String SECURITY_POLICY =
      "default-src 'none'; style-src 'self'; form-action 'self'; frame-ancestors 'none';"
          + " base-uri 'none'";

  private record Response(int status, String contentType, String body) {
    static Response html(int status, String page) {
      return new Response(status, HTML, page);
    }
  }

  private final DurastepClient client;
  private final String stylesheet;

  Router(DurastepClient client) {
    this.client = client;
    this.stylesheet = loadStylesheet();
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      String method = exchange.getRequestMethod();
      boolean head = method.equals("HEAD");
      Response response;
      if (!head && !method.equals("GET")) {
        exchange.getResponseHeaders().set("Allow", "GET, HEAD");
        response = error(405, "Read-only", "This dashboard only shows workflows.");
      } else {
        response = respond(exchange.getRequestURI().getRawPath(), exchange.getRequestURI());
      }
      send(exchange, response, head);
    }
  }

  private Response respond(String path, URI uri) {
    try {
      return switch (path) {
        case "/" -> Response.html(200, OverviewPage.render(client));
        case "/workflows" ->
            Response.html(
                200, WorkflowListPage.render(client, Parameters.parse(uri.getRawQuery())));
        case "/style.css" -> new Response(200, CSS, stylesheet);
        default -> error(404, "Not found", "There is no page at this address.");
      };
    } catch (BadRequestException e) {
      return error(400, "Bad request", e.getMessage());
    } catch (RuntimeException e) {
      log.warn("the dashboard could not show {}", path, e);
      return error(
          500,
          "Something went wrong",
          "The dashboard could not read the database. The details are in its log.");
    }
  }

  private static Response error(int status, String title, String message) {
    return Response.html(
        status,
        Html.page(title, Html.Section.NONE, false, "<p>" + Html.escape(message) + "</p>\n"));
  }

  private static void send(HttpExchange exchange, Response response, boolean head)
      throws IOException {
    Headers headers = exchange.getResponseHeaders();
    headers.set("Content-Type", response.contentType());
    headers.set("Cache-Control", "no-store");
    headers.set("Content-Security-Policy", SECURITY_POLICY);
    headers.set("X-Content-Type-Options", "nosniff");
    headers.set("Referrer-Policy", "no-referrer");
    if (head) {
      exchange.sendResponseHeaders(response.status(), -1);
      return;
    }
    byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(response.status(), bytes.length);
    try (OutputStream body = exchange.getResponseBody()) {
      body.write(bytes);
    }
  }

  private static String loadStylesheet() {
    try (InputStream css = Router.class.getResourceAsStream("dashboard.css")) {
      if (css == null) {
        throw new IllegalStateException("the dashboard's stylesheet is missing");
      }
      return new String(css.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
