package com.rudra.chatserver;

import com.rudra.chatserver.dao.MessageDAO;
import com.rudra.chatserver.dao.UserDAO;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

/**
 * Handles a single connected client.
 * Responsibilities:
 *  - Perform authentication (REGISTER / LOGIN)
 *  - After auth, process chat messages
 *  - Save all chat messages to DB
 *  - Notify server to update active‑user list
 */
public class ClientHandler implements Runnable {

    private final Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private String username;

    public ClientHandler(Socket socket) {
        this.socket = socket;
    }

    public String getUsername() {
        return username;
    }

    public void send(String msg) {
        if (out != null) {
            out.println(msg);
        }
    }

    @Override
    public void run() {
        try {
            in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true);

            // ===== Authentication loop =====
            while (username == null) {
                String line = in.readLine();
                if (line == null) {
                    // client disconnected before login
                    return;
                }
                handleAuth(line);
            }

            // Add to active clients set
            ChatServer.clients.add(this);
            ChatServer.updateUsers();

            // Record login event as a system message in the DB
            MessageDAO.saveMessage("SYSTEM", username + " logged in");

            // ===== Main message loop =====
            String line;
            while ((line = in.readLine()) != null) {
                handleMessage(line);
            }
        } catch (IOException e) {
            System.err.println("[SERVER] IO error for user " + username + ": " + e.getMessage());
        } finally {
            // Clean‑up on disconnect
            try {
                if (username != null) {
                    System.out.println("[SERVER] User disconnected: " + username);
                    MessageDAO.saveMessage("SYSTEM", username + " disconnected");
                }
            } catch (Exception ignore) {
            }

            ChatServer.removeClient(this);

            try {
                if (in != null) in.close();
            } catch (IOException ignored) {}
            if (out != null) out.close();
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    /**
     * Handle registration / login messages.
     * Expected formats:
     *   REGISTER|username|password
     *   LOGIN|username|password
     */
    private void handleAuth(String line) {
        String[] parts = line.split("\\|", 3);
        if (parts.length < 3) {
            out.println("AUTH_ERROR|Invalid auth format");
            return;
        }

        String cmd  = parts[0];
        String user = parts[1];
        String pass = parts[2];

        if ("REGISTER".equalsIgnoreCase(cmd)) {
            boolean ok = UserDAO.register(user, pass);
            if (ok) {
                this.username = user;
                out.println("REGISTER_OK");
            } else {
                out.println("REGISTER_FAIL|Username taken");
            }
        } else if ("LOGIN".equalsIgnoreCase(cmd)) {
            boolean ok = UserDAO.login(user, pass);
            if (ok) {
                this.username = user;
                out.println("LOGIN_OK");
            } else {
                out.println("LOGIN_FAIL|Invalid credentials");
            }
        } else {
            out.println("AUTH_ERROR|Unknown command");
        }
    }

    /**
     * Handle post‑login protocol messages.
     * For chat:
     *   MSG|receiverUsername|message text
     */
    private void handleMessage(String line) {
        if (line == null || line.isBlank()) return;

        if (line.startsWith("MSG|")) {
            String[] parts = line.split("\\|", 3);
            if (parts.length < 3) {
                send("ERROR|Invalid message format");
                return;
            }

            String toUser  = parts[1];
            String content = parts[2];

            // Persist message in DB (sender + receiver + content)
            MessageDAO.saveMessage(username, toUser, content);

            // Deliver to receiver (if online)
            String payloadToReceiver = "MSG|" + username + "|" + content;
            ChatServer.sendPrivate(toUser, payloadToReceiver);

            // Echo to sender so they see their own outgoing message nicely formatted
            String payloadToSender = "MSG_SELF|" + toUser + "|" + content;
            send(payloadToSender);
        } else {
            // Unknown / unsupported command
            send("ERROR|Unknown command");
        }
    }

}