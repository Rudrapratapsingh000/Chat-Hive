# Java Socket Chat Application (Direct Messaging + Active Users)

## Aim
To implement a client–server chat system using Java sockets and MySQL (via Hibernate) where:
- Users can **register/login**.
- Users can see **all active users and the active user count**.
- Each message is sent from **exactly one sender to exactly one selected receiver** (1‑to‑1).
- All **messages and login events are stored in the database**.

## High‑Level Architecture
- **chat-server** (Maven module)
  - `ChatServer` – TCP server, manages client connections and active user list.
  - `ClientHandler` – per‑client thread, handles auth + messages.
  - `dao` package – `UserDAO`, `MessageDAO` use Hibernate to talk to MySQL.
  - `entity` package – `User`, `Message` mapped to `users`, `messages` tables.

- **chat-client** (Maven module)
  - `ChatClientSwing` – Swing UI client.
    - Login/Register dialog.
    - Chat panel (messages).
    - Active Users panel with **count**.
    - 1‑to‑1 messaging: user selects a recipient then sends a message.

## Message Protocol (Text Lines)
### Authentication
- `REGISTER|username|password`
  - Server → `REGISTER_OK` or `REGISTER_FAIL|reason`
- `LOGIN|username|password`
  - Server → `LOGIN_OK` or `LOGIN_FAIL|reason`

### Active Users
- `USERS|user1,user2,user3`
  - Client updates the right‑side Active Users list.
  - Count is shown as: *Online users: N*.

### Chat Messages (1‑to‑1)
- Client → Server: `MSG|receiverUsername|message text`
- Server:
  - Stores: `sender`, `content = "TO receiverUsername: message text"`
  - Sends to receiver: `MSG|senderUsername|message text`
  - Echoes back to sender: `MSG_SELF|receiverUsername|message text`

## Database
Use the provided `database.sql`:

- `users(id, username, password)`
- `messages(id, sender, content, sent_at DEFAULT CURRENT_TIMESTAMP)`

> **Note**: Do not change your existing `hibernate.cfg.xml` credentials.
> The server uses that file to connect with your MySQL `root` and password.

Login and disconnect events are also saved as messages with:
- `sender = "SYSTEM"`
- `content = "<username> logged in"` or `"disconnected"`.

## How to Run (VS Code + Maven)

1. Import the **parent `pom.xml`** as a Maven project in VS Code.
2. Make sure MySQL is running and execute `database.sql` once.
3. Update `hibernate.cfg.xml` *only if* your DB name/host/port are different.
4. Build:
   - `mvn clean install`
5. Run the server (from `chat-server` module):
   - Main class: `com.rudra.chatserver.ChatServer`
6. Run one or more clients (from `chat-client` module):
   - Main class: `com.rudra.chatclient.ChatClientSwing`
7. Register or login from each client, then:
   - Select a user in **Active Users** list.
   - Type a message and click **Send**.

The UI will show:
- `You -> <user>: ...` for sent messages.
- `<user> -> You: ...` for received messages.
- Online user count at the top‑right.
