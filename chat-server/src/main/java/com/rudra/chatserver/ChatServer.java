package com.rudra.chatserver;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Main TCP chat server.
 * - Listens on PORT
 * - Accepts client connections
 * - Keeps track of connected clients
 * - Broadcasts the active user list
 * - Provides a helper for sending private messages
 */
public class ChatServer {

    public static final int PORT = 5000;

    // All connected clients (thread‑safe)
    public static final Set<ClientHandler> clients =
            Collections.synchronizedSet(new HashSet<>());

    public static void main(String[] args) {
        System.out.println("[SERVER] Starting chat server on port " + PORT + "...");
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("[SERVER] Server is up. Waiting for clients ...");

            while (true) {
                Socket socket = serverSocket.accept();
                System.out.println("[SERVER] New client connected: " + socket.getRemoteSocketAddress());
                ClientHandler handler = new ClientHandler(socket);
                Thread t = new Thread(handler);
                t.start();
            }
        } catch (IOException e) {
            System.err.println("[SERVER] Fatal server error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Send a message to all connected clients.
     */
    public static void broadcast(String message) {
        synchronized (clients) {
            for (ClientHandler c : clients) {
                c.send(message);
            }
        }
    }

    /**
     * Send a chat message only to the sender and the given receiver.
     * Format delivered to clients:
     *   MSG|<sender>|<content>
     */
    public static void sendToParticipants(String fromUsername, String toUsername, String content) {
        if (fromUsername == null || toUsername == null) {
            return;
        }
        String payload = "MSG|" + fromUsername + "|" + content;
        synchronized (clients) {
            for (ClientHandler c : clients) {
                String u = c.getUsername();
                if (u == null) continue;
                if (u.equals(fromUsername) || u.equals(toUsername)) {
                    c.send(payload);
                }
            }
        }
    }

    /**
     * Send a message only to a particular user (if online).
     */
    public static void sendPrivate(String recipientUsername, String message) {
        synchronized (clients) {
            for (ClientHandler c : clients) {
                if (recipientUsername != null &&
                        recipientUsername.equalsIgnoreCase(c.getUsername())) {
                    c.send(message);
                    break;
                }
            }
        }
    }

    /**
     * Remove a disconnected client and refresh active user list.
     */
    public static void removeClient(ClientHandler handler) {
        if (handler != null) {
            clients.remove(handler);
            updateUsers();
        }
    }

    /**
     * Build and broadcast the list of all currently connected usernames.
     * Protocol:  USERS|<count>|user1,user2,user3
     * The client uses the count to show how many users are active.
     */
    public static void updateUsers() {
        StringBuilder usersCsv = new StringBuilder();
        int count = 0;
        synchronized (clients) {
            for (ClientHandler c : clients) {
                if (c.getUsername() != null) {
                    if (count > 0) {
                        usersCsv.append(",");
                    }
                    usersCsv.append(c.getUsername());
                    count++;
                }
            }
        }
        String payload = "USERS|" + count + "|" + usersCsv;
        broadcast(payload);
    }
}

