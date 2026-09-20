package com.rudra.chatclient;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * ChatHive – Modern Telegram-style Java chat client UI.
 *
 * Protocol (matches your server):
 *
 * AUTH (client -> server):
 *   REGISTER|username|password
 *   LOGIN|username|password
 *
 * AUTH (server -> client):
 *   REGISTER_OK / REGISTER_FAIL|reason
 *   LOGIN_OK / LOGIN_FAIL|reason
 *
 * USER LIST (server -> client):
 *   USERS|<count>|user1,user2,...
 *
 * MESSAGES:
 *   client -> server:
 *     MSG|receiverUsername|message text
 *
 *   server -> client:
 *     MSG_SELF|receiverUsername|message text   (own sent message)
 *     MSG|senderUsername|message text          (incoming message)
 *     MSG|SYSTEM|some system text (optional)
 */
public class ChatClientSwing extends JFrame {

    // Networking
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private String username;

    // Card layout for login vs chat
    private CardLayout cardLayout;
    private JPanel rootPanel;

    // Login UI
    private JLabel loginStatusLabel;
    private JTextField loginUserField;
    private JPasswordField loginPassField;
    private JComboBox<String> loginActionBox;

    // Chat UI
    private JLabel chatStatusLabel;
    private JLabel activeCountLabel;
    private DefaultListModel<String> usersModel;
    private JList<String> usersList;

    private JTextField messageField;
    private JButton sendButton;
    private JButton emojiButton;
    private JLabel typingLabel;

    private JPanel messageListPanel;
    private JScrollPane messageScrollPane;
    private JPopupMenu emojiPopup;

    // Typing animation
    private Timer typingIdleTimer;
    private Timer typingAnimTimer;
    private int typingDots = 0;

    // Time format for timestamps
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm");

    public ChatClientSwing() {
        setTitle("ChatHive");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(950, 580);
        setLocationRelativeTo(null);

        cardLayout = new CardLayout();
        rootPanel = new JPanel(cardLayout);
        getContentPane().add(rootPanel);

        initLoginUI();
        initChatUI();
        initWindowListener();

        try {
            connectToServer();
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this,
                    "Unable to connect to server: " + e.getMessage(),
                    "Connection error",
                    JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }

        // show login card
        cardLayout.show(rootPanel, "login");
    }

    // ============================================================
    //  LOGIN PAGE – 3D / Aesthetic
    // ============================================================
    private void initLoginUI() {
        JPanel loginBackground = new GradientBackgroundPanel();
        loginBackground.setLayout(new GridBagLayout());
        loginBackground.setBorder(new EmptyBorder(20, 20, 20, 20));

        GlassCardPanel loginCard = new GlassCardPanel();
        loginCard.setLayout(new GridBagLayout());
        loginCard.setBorder(new EmptyBorder(20, 20, 20, 20));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.anchor = GridBagConstraints.CENTER;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridx = 0;
        gbc.weightx = 1.0;

        // Iconic header
        JLabel iconLabel = new JLabel("🐝", SwingConstants.CENTER);
        iconLabel.setFont(iconLabel.getFont().deriveFont(42f));
        gbc.gridy = 0;
        loginCard.add(iconLabel, gbc);

        JLabel titleLabel = new JLabel("Welcome to ChatHive", SwingConstants.CENTER);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 20f));
        titleLabel.setForeground(new Color(40, 60, 90));
        gbc.gridy = 1;
        loginCard.add(titleLabel, gbc);

        JLabel subtitleLabel = new JLabel("messaging", SwingConstants.CENTER);
        subtitleLabel.setFont(subtitleLabel.getFont().deriveFont(13f));
        subtitleLabel.setForeground(new Color(90, 110, 140));
        gbc.gridy = 2;
        loginCard.add(subtitleLabel, gbc);

        // Action (Login / Register)
        JPanel actionPanel = new JPanel(new BorderLayout(5, 5));
        actionPanel.setOpaque(false);
        loginActionBox = new JComboBox<>(new String[]{"Login", "Register"});
        actionPanel.add(new JLabel("Action:"), BorderLayout.WEST);
        actionPanel.add(loginActionBox, BorderLayout.CENTER);
        gbc.gridy = 3;
        loginCard.add(actionPanel, gbc);

        // Username
        JPanel userPanel = new JPanel(new BorderLayout(5, 5));
        userPanel.setOpaque(false);
        loginUserField = new JTextField(16);
        userPanel.add(new JLabel("Username:"), BorderLayout.WEST);
        userPanel.add(loginUserField, BorderLayout.CENTER);
        gbc.gridy = 4;
        loginCard.add(userPanel, gbc);

        // Password
        JPanel passPanel = new JPanel(new BorderLayout(5, 5));
        passPanel.setOpaque(false);
        loginPassField = new JPasswordField(16);
        passPanel.add(new JLabel("Password:"), BorderLayout.WEST);
        passPanel.add(loginPassField, BorderLayout.CENTER);
        gbc.gridy = 5;
        loginCard.add(passPanel, gbc);

        // Status
        loginStatusLabel = new JLabel("Connecting to server...", SwingConstants.CENTER);
        loginStatusLabel.setFont(loginStatusLabel.getFont().deriveFont(11f));
        loginStatusLabel.setForeground(new Color(80, 100, 130));
        gbc.gridy = 6;
        loginCard.add(loginStatusLabel, gbc);

        // Button
        JButton proceedButton = new JButton("Enter ChatHive ➜");
        proceedButton.setFocusPainted(false);
        proceedButton.setFont(proceedButton.getFont().deriveFont(Font.BOLD, 14f));
        proceedButton.addActionListener(e -> onLoginProceed());
        gbc.gridy = 7;
        loginCard.add(proceedButton, gbc);

        GridBagConstraints bgc = new GridBagConstraints();
        bgc.gridx = 0; bgc.gridy = 0;
        bgc.weightx = 1.0; bgc.weighty = 1.0;
        loginBackground.add(loginCard, bgc);

        rootPanel.add(loginBackground, "login");
    }

    private void onLoginProceed() {
        String u = loginUserField.getText().trim();
        String p = new String(loginPassField.getPassword());

        if (u.isEmpty() || p.isEmpty()) {
            loginStatusLabel.setText("⚠ Username and password cannot be empty.");
            return;
        }

        String action = (String) loginActionBox.getSelectedItem();
        try {
            if ("Register".equalsIgnoreCase(action)) {
                out.println("REGISTER|" + u + "|" + p);
                String reply = in.readLine();
                if (reply == null) throw new IOException("Server closed connection");
                if ("REGISTER_OK".equalsIgnoreCase(reply)) {
                    username = u;
                    JOptionPane.showMessageDialog(this,
                            "Registration successful. Logged in as " + username,
                            "Success",
                            JOptionPane.INFORMATION_MESSAGE);
                    switchToChat();
                } else {
                    loginStatusLabel.setText("❌ " + reply);
                }
            } else { // Login
                out.println("LOGIN|" + u + "|" + p);
                String reply = in.readLine();
                if (reply == null) throw new IOException("Server closed connection");
                if ("LOGIN_OK".equalsIgnoreCase(reply)) {
                    username = u;
                    JOptionPane.showMessageDialog(this,
                            "Login successful. Welcome " + username + "!",
                            "Success",
                            JOptionPane.INFORMATION_MESSAGE);
                    switchToChat();
                } else {
                    loginStatusLabel.setText("❌ " + reply);
                }
            }
        } catch (IOException ex) {
            loginStatusLabel.setText("❌ Auth error: " + ex.getMessage());
        }
    }

    private void switchToChat() {
        setTitle("ChatHive - " + username);
        chatStatusLabel.setText("Logged in as: " + username);
        cardLayout.show(rootPanel, "chat");
        addSystemMessage("You are now online as " + username + ". ✅");
        startReaderThread();
    }

    // ============================================================
    //  CHAT PAGE
    // ============================================================
    private void initChatUI() {
        JPanel chatRoot = new JPanel(new BorderLayout());
        chatRoot.setBackground(new Color(225, 230, 238));

        // Top bar
        JPanel topBar = new JPanel(new BorderLayout());
        topBar.setBackground(new Color(40, 120, 200));
        topBar.setBorder(new EmptyBorder(8, 12, 8, 12));

        JLabel titleLabel = new JLabel("ChatHive");
        titleLabel.setForeground(Color.WHITE);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 16f));

        chatStatusLabel = new JLabel("Not logged in");
        chatStatusLabel.setForeground(new Color(220, 235, 255));
        chatStatusLabel.setHorizontalAlignment(SwingConstants.RIGHT);

        topBar.add(titleLabel, BorderLayout.WEST);
        topBar.add(chatStatusLabel, BorderLayout.EAST);
        chatRoot.add(topBar, BorderLayout.NORTH);

        // Center area
        JPanel centerPanel = new JPanel(new BorderLayout());
        centerPanel.setBorder(new EmptyBorder(8, 8, 8, 8));
        centerPanel.setBackground(new Color(225, 230, 238));

        // Messages with background art
        messageListPanel = new ChatBackgroundPanel();
        messageListPanel.setLayout(new BoxLayout(messageListPanel, BoxLayout.Y_AXIS));

        messageScrollPane = new JScrollPane(messageListPanel);
        messageScrollPane.setBorder(BorderFactory.createEmptyBorder());
        messageScrollPane.getVerticalScrollBar().setUnitIncrement(16);

        centerPanel.add(messageScrollPane, BorderLayout.CENTER);

        // Users list
        usersModel = new DefaultListModel<>();
        usersList = new JList<>(usersModel);
        usersList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        usersList.setFont(usersList.getFont().deriveFont(13f));

        JScrollPane usersScroll = new JScrollPane(usersList);
        usersScroll.setBorder(BorderFactory.createEmptyBorder());

        activeCountLabel = new JLabel("Online users: 0");
        activeCountLabel.setHorizontalAlignment(SwingConstants.CENTER);
        activeCountLabel.setFont(activeCountLabel.getFont().deriveFont(Font.BOLD, 13f));

        JPanel usersPanel = new JPanel(new BorderLayout());
        usersPanel.setPreferredSize(new Dimension(230, 0));
        usersPanel.setBackground(Color.WHITE);
        usersPanel.setBorder(new EmptyBorder(8, 8, 8, 8));
        usersPanel.add(activeCountLabel, BorderLayout.NORTH);
        usersPanel.add(usersScroll, BorderLayout.CENTER);

        centerPanel.add(usersPanel, BorderLayout.EAST);

        chatRoot.add(centerPanel, BorderLayout.CENTER);

        // Bottom input + typing indicator
        JPanel bottomPanel = new JPanel(new BorderLayout(8, 4));
        bottomPanel.setBorder(new EmptyBorder(8, 8, 4, 8));
        bottomPanel.setBackground(new Color(240, 242, 246));

        emojiButton = new JButton("😊");
        emojiButton.setFocusPainted(false);
        emojiButton.setMargin(new Insets(2, 8, 2, 8));
        emojiButton.addActionListener(e -> showEmojiPopup(emojiButton));

        messageField = new JTextField();
        messageField.setFont(messageField.getFont().deriveFont(14f));
        messageField.addActionListener(e -> sendMessage());

        // typing indicator logic
        messageField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { onUserTyping(); }
            @Override public void removeUpdate(DocumentEvent e) { onUserTyping(); }
            @Override public void changedUpdate(DocumentEvent e) { onUserTyping(); }
        });

        sendButton = new JButton("Send");
        sendButton.setFocusPainted(false);
        sendButton.setFont(sendButton.getFont().deriveFont(Font.BOLD, 13f));
        sendButton.addActionListener(e -> sendMessage());

        JPanel leftInputPanel = new JPanel(new BorderLayout(5, 5));
        leftInputPanel.setOpaque(false);
        leftInputPanel.add(emojiButton, BorderLayout.WEST);
        leftInputPanel.add(messageField, BorderLayout.CENTER);

        bottomPanel.add(leftInputPanel, BorderLayout.CENTER);
        bottomPanel.add(sendButton, BorderLayout.EAST);

        // Typing label (animated "You are typing...")
        typingLabel = new JLabel(" ");
        typingLabel.setFont(typingLabel.getFont().deriveFont(11f));
        typingLabel.setForeground(new Color(120, 130, 150));
        typingLabel.setBorder(new EmptyBorder(0, 4, 4, 4));

        JPanel bottomWrapper = new JPanel(new BorderLayout());
        bottomWrapper.setBackground(bottomPanel.getBackground());
        bottomWrapper.add(bottomPanel, BorderLayout.CENTER);
        bottomWrapper.add(typingLabel, BorderLayout.SOUTH);

        chatRoot.add(bottomWrapper, BorderLayout.SOUTH);

        rootPanel.add(chatRoot, "chat");
    }

    // ============================================================
    //  WINDOW & NETWORK
    // ============================================================
    private void initWindowListener() {
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                try {
                    if (out != null) out.flush();
                    if (socket != null && !socket.isClosed()) {
                        socket.close();
                    }
                } catch (IOException ignored) {}
            }
        });
    }

    private void connectToServer() throws IOException {
        String host = "localhost";
        int port = 5000;
        socket = new Socket(host, port);
        in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        out = new PrintWriter(socket.getOutputStream(), true);
        if (loginStatusLabel != null) {
            loginStatusLabel.setText("Connected to " + host + ":" + port);
        }
    }

    private void startReaderThread() {
        Thread t = new Thread(this::listenToServer);
        t.setDaemon(true);
        t.start();
    }

    private void listenToServer() {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                handleServerMessage(line);
            }
        } catch (IOException e) {
            addSystemMessage("Disconnected from server: " + e.getMessage());
        }
    }

    // ============================================================
    //  SERVER MESSAGE HANDLING
    // ============================================================
    private void handleServerMessage(String line) {
        if (line.startsWith("USERS|")) {
            // USERS|<count>|user1,user2,...
            String[] parts = line.split("\\|", 3);
            if (parts.length >= 3) {
                String countStr = parts[1];
                String usersCsv = parts[2];

                int count = 0;
                try { count = Integer.parseInt(countStr); } catch (NumberFormatException ignored) {}

                final int activeCount = count;
                SwingUtilities.invokeLater(() -> {
                    usersModel.clear();
                    if (!usersCsv.isEmpty()) {
                        String[] arr = usersCsv.split(",");
                        for (String u : arr) {
                            if (!u.isBlank()) usersModel.addElement(u.trim());
                        }
                    }
                    activeCountLabel.setText("Online users: " + activeCount);
                });
            }

        } else if (line.startsWith("MSG_SELF|")) {
            // MSG_SELF|toUser|content
            String[] parts = line.split("\\|", 3);
            if (parts.length == 3) {
                String toUser = parts[1];
                String content = parts[2];
                addOutgoingMessage("You → " + toUser, content, username);
            }

        } else if (line.startsWith("MSG|")) {
            // MSG|fromUser|content
            String[] parts = line.split("\\|", 3);
            if (parts.length == 3) {
                String fromUser = parts[1];
                String content = parts[2];

                if ("SYSTEM".equalsIgnoreCase(fromUser)) {
                    addSystemMessage(content);
                } else {
                    addIncomingMessage(fromUser, content);
                }
            }

        } else {
            addSystemMessage("[Server] " + line);
        }
    }

    // ============================================================
    //  MESSAGE BUBBLES / AVATARS / COLORS / TIMESTAMPS
    // ============================================================
    private void addSystemMessage(String text) {
        addMessageBubble("SYSTEM", text, false, true);
    }

    private void addIncomingMessage(String fromUser, String text) {
        addMessageBubble(fromUser, text, false, false);
    }

    private void addOutgoingMessage(String label, String text, String colorKey) {
        // label shown as "You → Abhi", but avatar & color based on our username
        addMessageBubble(label, text, true, false, colorKey);
    }

    private void addMessageBubble(String userLabel, String text, boolean outgoing, boolean system) {
        addMessageBubble(userLabel, text, outgoing, system, userLabel);
    }

    private void addMessageBubble(String userLabel,
                                  String text,
                                  boolean outgoing,
                                  boolean system,
                                  String colorKey) {

        SwingUtilities.invokeLater(() -> {
            JPanel outer = new JPanel(new BorderLayout());
            outer.setOpaque(false);
            outer.setBorder(new EmptyBorder(4, 8, 4, 8));

            Color bubbleBg;
            int align;
            if (system) {
                bubbleBg = new Color(220, 220, 230);
                align = SwingConstants.CENTER;
            } else if (outgoing) {
                bubbleBg = new Color(200, 240, 255);
                align = SwingConstants.RIGHT;
            } else {
                bubbleBg = Color.WHITE;
                align = SwingConstants.LEFT;
            }

            // Inner panel with avatar + content
            JPanel contentPanel = new JPanel(new BorderLayout(6, 0));
            contentPanel.setOpaque(false);

            if (!system) {
                AvatarCircle avatar = new AvatarCircle(colorKey);
                if (align == SwingConstants.RIGHT) {
                    contentPanel.add(avatar, BorderLayout.EAST);
                } else {
                    contentPanel.add(avatar, BorderLayout.WEST);
                }
            }

            MessageBubble bubble = new MessageBubble(userLabel, text, bubbleBg, system, colorKey);
            if (align == SwingConstants.RIGHT) {
                contentPanel.add(bubble, BorderLayout.CENTER);
                outer.add(contentPanel, BorderLayout.EAST);
            } else if (align == SwingConstants.LEFT) {
                contentPanel.add(bubble, BorderLayout.CENTER);
                outer.add(contentPanel, BorderLayout.WEST);
            } else {
                outer.add(bubble, BorderLayout.CENTER);
            }

            messageListPanel.add(outer);
            messageListPanel.revalidate();
            messageListPanel.repaint();

            SwingUtilities.invokeLater(() -> {
                JScrollBar bar = messageScrollPane.getVerticalScrollBar();
                bar.setValue(bar.getMaximum());
            });
        });
    }

    // Avatar circle showing first letter with color derived from username
    private static class AvatarCircle extends JComponent {
        private final String initial;
        private final Color color;

        public AvatarCircle(String name) {
            this.initial = (name != null && !name.isEmpty())
                    ? name.trim().substring(0, 1).toUpperCase()
                    : "?";
            this.color = colorForName(name);
            setPreferredSize(new Dimension(32, 32));
            setMinimumSize(new Dimension(32, 32));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int size = Math.min(getWidth(), getHeight());
            int x = (getWidth() - size) / 2;
            int y = (getHeight() - size) / 2;

            g2.setColor(color);
            g2.fillOval(x, y, size, size);

            g2.setColor(Color.WHITE);
            g2.setFont(getFont().deriveFont(Font.BOLD, 14f));
            FontMetrics fm = g2.getFontMetrics();
            int tx = x + (size - fm.stringWidth(initial)) / 2;
            int ty = y + (size + fm.getAscent() - fm.getDescent()) / 2;
            g2.drawString(initial, tx, ty);

            g2.dispose();
        }
    }

    // Actual message bubble panel
    private static class MessageBubble extends JPanel {
        private final String userLabel;
        private final String text;
        private final Color bg;
        private final boolean system;
        private final Color nameColor;
        private final String timestamp;

        public MessageBubble(String userLabel,
                             String text,
                             Color bg,
                             boolean system,
                             String colorKey) {
            this.userLabel = userLabel;
            this.text = text;
            this.bg = bg;
            this.system = system;
            this.nameColor = system ? new Color(80, 90, 110) : colorForName(colorKey);
            this.timestamp = LocalTime.now().format(TIME_FORMAT);

            setOpaque(false);
            setBorder(new EmptyBorder(8, 12, 8, 12));
            setLayout(new BorderLayout());
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int arc = 18;
            g2.setColor(bg);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
            g2.dispose();
            super.paintComponent(g);
        }

        @Override
        public void addNotify() {
            super.addNotify();
            removeAll();

            JPanel inner = new JPanel();
            inner.setOpaque(false);
            inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));

            if (!system) {
                JLabel nameLabel = new JLabel(userLabel);
                nameLabel.setForeground(nameColor);
                nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 12f));
                inner.add(nameLabel);
            }

            JLabel msgLabel = new JLabel("<html><body style='width:260px'>" + text + "</body></html>");
            msgLabel.setForeground(Color.BLACK);
            msgLabel.setFont(msgLabel.getFont().deriveFont(system ? 11f : 13f));
            inner.add(msgLabel);

            JLabel timeLabel = new JLabel(timestamp);
            timeLabel.setFont(timeLabel.getFont().deriveFont(10f));
            timeLabel.setForeground(new Color(120, 130, 150));
            timeLabel.setAlignmentX(Component.RIGHT_ALIGNMENT);
            inner.add(Box.createVerticalStrut(2));
            inner.add(timeLabel);

            add(inner, BorderLayout.CENTER);
        }

        @Override
        public boolean isOpaque() {
            return false;
        }
    }

    // Deterministic pretty color for each username
    private static Color colorForName(String name) {
        if (name == null) name = "";
        int hash = Math.abs(name.hashCode());
        Color[] palette = new Color[]{
                new Color(0xFF8A65),
                new Color(0x4DB6AC),
                new Color(0x9575CD),
                new Color(0xBA68C8),
                new Color(0x4FC3F7),
                new Color(0x81C784),
                new Color(0xFFB74D),
                new Color(0xE57373)
        };
        return palette[hash % palette.length];
    }

    // ============================================================
    //  BACKGROUNDS (3D-ish)
    // ============================================================
    private static class GradientBackgroundPanel extends JPanel {
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color c1 = new Color(52, 143, 235);
            Color c2 = new Color(88, 214, 141);
            GradientPaint gp = new GradientPaint(0, 0, c1, getWidth(), getHeight(), c2);
            g2.setPaint(gp);
            g2.fillRect(0, 0, getWidth(), getHeight());

            g2.setColor(new Color(255, 255, 255, 40));
            g2.fillOval(getWidth() - 200, 40, 160, 160);
            g2.fillOval(40, getHeight() - 200, 180, 180);

            g2.dispose();
        }
    }

    private static class GlassCardPanel extends JPanel {
        public GlassCardPanel() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int arc = 30;
            int x = 0;
            int y = 0;
            int w = getWidth();
            int h = getHeight();

            g2.setColor(new Color(0, 0, 0, 60));
            g2.fillRoundRect(x + 4, y + 6, w - 8, h - 8, arc, arc);

            g2.setColor(new Color(255, 255, 255, 190));
            g2.fillRoundRect(x, y, w - 8, h - 8, arc, arc);

            g2.dispose();
            super.paintComponent(g);
        }
    }

    private static class ChatBackgroundPanel extends JPanel {
        public ChatBackgroundPanel() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            Color c1 = new Color(235, 240, 246);
            Color c2 = new Color(215, 225, 238);
            GradientPaint gp = new GradientPaint(0, 0, c1, 0, getHeight(), c2);
            g2.setPaint(gp);
            g2.fillRect(0, 0, getWidth(), getHeight());

            g2.setColor(new Color(200, 220, 240, 120));
            g2.fillRoundRect(40, 50, 180, 40, 20, 20);
            g2.fillRoundRect(60, 100, 220, 40, 20, 20);

            g2.setColor(new Color(255, 255, 255, 200));
            g2.fillRoundRect(getWidth() - 260, 60, 200, 40, 20, 20);
            g2.fillRoundRect(getWidth() - 300, 110, 230, 40, 20, 20);

            g2.dispose();
        }
    }

    // ============================================================
    //  EMOJI POPUP
    // ============================================================
    private void showEmojiPopup(Component invoker) {
        if (emojiPopup == null) {
            emojiPopup = new JPopupMenu();
            JPanel panel = new JPanel(new GridLayout(2, 4, 4, 4));
            panel.setBorder(new EmptyBorder(4, 4, 4, 4));

            String[] emojis = {"😀", "😂", "😍", "😢", "👍", "🔥", "❤️", "😎"};
            for (String emo : emojis) {
                JButton btn = new JButton(emo);
                btn.setMargin(new Insets(2, 2, 2, 2));
                btn.setFocusPainted(false);
                btn.addActionListener(e -> {
                    messageField.setText(messageField.getText() + emo);
                    messageField.requestFocusInWindow();
                    emojiPopup.setVisible(false);
                });
                panel.add(btn);
            }

            emojiPopup.add(panel);
        }

        emojiPopup.show(invoker, 0, -emojiPopup.getPreferredSize().height);
    }

    // ============================================================
    //  TYPING INDICATOR (local "You are typing…" animation)
    // ============================================================
    private void onUserTyping() {
        String current = messageField.getText();
        if (current.isEmpty()) {
            stopTypingIndicator();
            return;
        }

        if (typingLabel.getText() == null || typingLabel.getText().isBlank()) {
            startTypingIndicator();
        }

        if (typingIdleTimer == null) {
            typingIdleTimer = new Timer(1500, e -> stopTypingIndicator());
            typingIdleTimer.setRepeats(false);
        }
        typingIdleTimer.restart();
    }

    private void startTypingIndicator() {
        typingDots = 0;
        typingLabel.setText("You are typing");
        if (typingAnimTimer == null) {
            typingAnimTimer = new Timer(350, e -> {
                typingDots = (typingDots + 1) % 4;
                StringBuilder sb = new StringBuilder("You are typing");
                for (int i = 0; i < typingDots; i++) sb.append(".");
                typingLabel.setText(sb.toString());
            });
        }
        typingAnimTimer.start();
    }

    private void stopTypingIndicator() {
        if (typingAnimTimer != null) {
            typingAnimTimer.stop();
        }
        typingLabel.setText(" ");
    }

    // ============================================================
    //  SEND MESSAGE
    // ============================================================
    private void sendMessage() {
        String text = messageField.getText().trim();
        if (text.isEmpty()) return;

        String toUser = usersList.getSelectedValue();
        if (toUser == null) {
            JOptionPane.showMessageDialog(this,
                    "Please select exactly one active user to send the message.",
                    "No recipient selected",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        out.println("MSG|" + toUser + "|" + text);
        messageField.setText("");
        stopTypingIndicator();
    }

    // ============================================================
    //  MAIN
    // ============================================================
    public static void main(String[] args) {
        try {
            for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equalsIgnoreCase(info.getName())) {
                    UIManager.setLookAndFeel(info.getClassName());
                    break;
                }
            }
        } catch (Exception ignored) {}

        SwingUtilities.invokeLater(() -> new ChatClientSwing().setVisible(true));
    }
}
