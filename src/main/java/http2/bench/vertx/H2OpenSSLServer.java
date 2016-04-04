package http2.bench.vertx;

import http2.bench.Env;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public class H2OpenSSLServer {

  public static void main(String[] args) {
    Vertx vertx = Vertx.vertx();
    vertx.deployVerticle(ServerVerticle.OPENSSL.class.getName(), new DeploymentOptions().setInstances(Env.numCore()), ar -> {
      if (ar.succeeded()) {
        System.out.println("Server started");
      } else {
        ar.cause().printStackTrace();
      }
    });
  }
}
