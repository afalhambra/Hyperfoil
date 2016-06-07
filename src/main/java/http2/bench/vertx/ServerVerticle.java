package http2.bench.vertx;

import http2.bench.Backend;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.file.AsyncFile;
import io.vertx.core.file.FileSystem;
import io.vertx.core.file.OpenOptions;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.JdkSSLEngineOptions;
import io.vertx.core.net.OpenSSLEngineOptions;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.core.net.SSLEngineOptions;
import io.vertx.core.streams.Pump;
import io.vertx.ext.asyncsql.AsyncSQLClient;
import io.vertx.ext.asyncsql.PostgreSQLClient;
import io.vertx.ext.sql.SQLConnection;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public class ServerVerticle extends AbstractVerticle {

  private static final AtomicBoolean dbInitialized = new AtomicBoolean();

  private SSLEngineOptions engine;
  private Backend backend;
  private int soAcceptBacklog;
  private int poolSize;
  private int sleepTime;
  private String backendHost;
  private int backendPort;
  private boolean clearText;

  public ServerVerticle() {
  }

  @Override
  public void start(Future<Void> startFuture) throws Exception {

    backend = Backend.valueOf(context.config().getString("backend"));
    soAcceptBacklog = context.config().getInteger("soAcceptBacklog");
    engine = config().getBoolean("openSSL") ? new OpenSSLEngineOptions() : new JdkSSLEngineOptions();
    poolSize = config().getInteger("poolSize");
    sleepTime = config().getInteger("sleepTime");
    backendHost = config().getString("backendHost");
    backendPort = config().getInteger("backendPort");
    clearText = config().getBoolean("clearText");

    HttpClient httpClient = vertx.createHttpClient(new HttpClientOptions().
        setKeepAlive(true).
        setPipelining(true).
//        setPipeliningLimit(100).
        setMaxPoolSize(poolSize));

    Future<Void> dbFuture = Future.future();
    AsyncSQLClient client;
    if (backend == Backend.DB) {
      JsonObject postgreSQLClientConfig = new JsonObject().
          put("host", backendHost).
          put("maxPoolSize", poolSize);
      client = PostgreSQLClient.createNonShared(vertx, postgreSQLClientConfig);
      if (dbInitialized.compareAndSet(false, true)) {
        client.getConnection(res -> {
          if (res.succeeded()) {
            SQLConnection conn = res.result();
            Future<Void> fut1 = Future.future();
            conn.execute("DROP TABLE IF EXISTS data_table", fut1.completer());
            fut1.compose(v -> {
              Future<Void> fut2 = Future.future();
              conn.execute("CREATE TABLE IF NOT EXISTS data_table (data text)", fut2.completer()).close();
              return fut2;
            }).setHandler(dbFuture.completer());
          } else {
            dbFuture.fail(res.cause());
          }
        });
      } else {
        dbFuture.complete();
      }
    } else {
      client = null;
      dbFuture.complete();
    }

    HttpServerOptions options = new HttpServerOptions()
        .setAcceptBacklog(soAcceptBacklog)
        .setPort(config().getInteger("port"));
    if (!clearText) {
      options.setSsl(true);
      options.setUseAlpn(true);
      options.setSslEngineOptions(engine);
      options.addEnabledCipherSuite("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256");
      options.setPemKeyCertOptions(new PemKeyCertOptions().setKeyPath("tls/server-key.pem").setCertPath("tls/server-cert.pem"));
    }

    HttpServer server = vertx.createHttpServer(options);

    FileSystem fs = vertx.fileSystem();
    if (!fs.existsBlocking("vertx.uploads")) {
      fs.mkdirsBlocking("vertx.uploads");
    }

    server.requestHandler(req -> {
      if (backend == Backend.DISK) {
        if (req.method() == HttpMethod.POST) {
          req.pause();
          String file = "vertx.uploads/" + UUID.randomUUID();
          fs.open(file, new OpenOptions().setCreate(true), ar1 -> {
            req.resume();
            if (ar1.succeeded()) {
              AsyncFile f = ar1.result();
              Pump pump = Pump.pump(req, f);
              pump.start();
              req.endHandler(v -> {
                f.close(ar2 -> fs.delete(file, ar3 -> {}));
                sendResponse(req, "<html><body>Hello World</body></html>");
              });
            } else {
              ar1.cause().printStackTrace();
              req.response().setStatusCode(500).end();
            }
          });
        } else {
          req.endHandler(v -> {
            sendResponse(req,"<html><body>Hello World</body></html>" );
          });
        }
      } else if (backend == Backend.DB) {
        if (req.method() == HttpMethod.POST) {
          req.bodyHandler(buff -> {
            client.getConnection(res -> {
              if (res.succeeded()) {
                SQLConnection conn = res.result();
                conn.queryWithParams("INSERT INTO data_table (data) VALUES (?)", new JsonArray().add(buff.toString()), ar -> {
                  if (ar.succeeded()) {
                    sendResponse(req, "<html><body>OK</body></html>");
                  } else {
                    req.response().setStatusCode(500).end();
                  }
                  conn.close();
                });
              } else {
                req.response().setStatusCode(500).end();
              }
            });
          });
        } else {
          client.getConnection(res -> {
            if (res.succeeded()) {
              SQLConnection conn = res.result();
              conn.query("SELECT pg_sleep(0.040)", ar -> {
                if (ar.succeeded()) {
                  sendResponse(req, "<html><body>OK</body></html>");
                } else {
                  req.response().setStatusCode(500).end();
                }
                conn.close();
              });
            } else {
              req.response().setStatusCode(500).end();
            }
          });
        }
      } else if (backend == Backend.MICROSERVICE) {
        if (req.method() == HttpMethod.POST) {
          req.bodyHandler(buff -> {
            HttpClientRequest clientReq = httpClient.post(backendPort, backendHost, "/", clientResp -> {
              clientResp.endHandler(v -> {
                sendResponse(req, "<html><body>OK</body></html>");
              });
            });
            clientReq.end(buff);
          });
        } else {
          req.endHandler(v1 -> {
            httpClient.getNow(backendPort, backendHost, "/", clientResp -> {
              clientResp.endHandler(v2 -> {
                sendResponse(req, "<html><body>OK</body></html>");
              });
            });
          });
        }
      } else {
        req.endHandler(v -> {
          sendResponse(req, "<html><body>Hello World / " + req.version() + "</body></html>");
        });
      }
    });

    Future<HttpServer> serverInit = Future.future();
    server.listen(serverInit.completer());

    CompositeFuture.all(dbFuture, serverInit).<Void>map(c -> null).setHandler(startFuture.completer());
  }

  private void sendResponse(HttpServerRequest req, String s) {
    if (sleepTime > 0) {
      vertx.setTimer(sleepTime, v -> {
        req.response().end(s);
      });
    } else {
      req.response().end(s);
    }
  }
}
