import java.io.IOException;

import core.Server;

public class Main {

    public static void main(String[] args) {
        Server server = new Server();
        try {
            server.start(8080);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
