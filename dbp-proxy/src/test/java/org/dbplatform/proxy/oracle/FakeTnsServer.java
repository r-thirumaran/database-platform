package org.dbplatform.proxy.oracle;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Scripted stand-in for an Oracle listener / server process. */
final class FakeTnsServer implements AutoCloseable {
    interface Script {
        void run(Session s) throws IOException;
    }

    static final class Session {
        final Socket socket;
        final InputStream in;
        final OutputStream out;

        Session(Socket socket) throws IOException {
            this.socket = socket;
            this.in = new BufferedInputStream(socket.getInputStream());
            this.out = socket.getOutputStream();
        }

        TnsConnectPacket readConnect() throws IOException {
            return OracleConnectionHandler.readConnectRequest(in);
        }

        TnsPacket read() throws IOException {
            return TnsPacket.read(in);
        }

        void write(byte[] b) throws IOException {
            out.write(b);
            out.flush();
        }

        int remotePort() {
            return socket.getPort();
        }
    }

    final ServerSocket server;
    final List<String> connectStrings = new CopyOnWriteArrayList<>();
    final List<Integer> remotePorts = new CopyOnWriteArrayList<>();
    final List<Boolean> deferredForms = new CopyOnWriteArrayList<>();
    final List<Throwable> errors = new CopyOnWriteArrayList<>();
    private final Script script;
    private volatile boolean running = true;

    FakeTnsServer(Script script) throws IOException {
        this.script = script;
        this.server = new ServerSocket(0, 16, java.net.InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            while (running) {
                try {
                    Socket s = server.accept();
                    Thread.ofVirtual().start(() -> serve(s));
                } catch (IOException e) {
                    return;
                }
            }
        });
    }

    private void serve(Socket s) {
        try (s) {
            script.run(new Session(s));
        } catch (Throwable t) {
            errors.add(t);
        }
    }

    int port() {
        return server.getLocalPort();
    }

    /** Reads a CONNECT (+ deferred DATA), records what the "listener" saw. */
    TnsConnectPacket record(Session s) throws IOException {
        TnsConnectPacket c = s.readConnect();
        if (c != null) {
            connectStrings.add(c.connectData());
            deferredForms.add(c.usesDeferredForm());
            remotePorts.add(s.remotePort());
        }
        return c;
    }

    /** ACCEPT and then echo every packet back until the client closes. */
    static void acceptAndEcho(Session s) throws IOException {
        s.write(TnsTestPackets.accept(318));
        TnsPacket p;
        while ((p = s.read()) != null) {
            s.write(p.bytes());
        }
    }

    @Override
    public void close() throws IOException {
        running = false;
        server.close();
    }
}
