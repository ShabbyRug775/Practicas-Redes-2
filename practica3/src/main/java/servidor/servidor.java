package servidor;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Servidor {
    private static final int SERVER_PORT = 4446;
    private static final int SERVER_PORTU = 4447;
    private static final int BUFFER_SIZE = 1024;
    private static final String MULTICAST_ADDRESS = "230.0.0.0";
    private static final String DEFAULT_SERVER_FOLDER = "./Server";
    private static final String GREEN = "\033[1;32m";
    private static final String RESET = "\033[0m";

    java.util.List<String> userList = Collections.synchronizedList(new ArrayList<>());
    private static final Map<String, InetSocketAddress> userAddresses = new ConcurrentHashMap<>();
    private static final Map<String, Set<InetSocketAddress>> chatRooms = new ConcurrentHashMap<>();
    private static final Map<String, java.util.List<String>> roomHistories = new ConcurrentHashMap<>();

    private static DatagramSocket fileSocket;
    private static DatagramSocket unicastSocket;

    private JFrame frame;
    private JTextArea logArea;
    private JList<String> userListView;
    private JList<String> roomListView;
    private DefaultListModel<String> userListModel;
    private DefaultListModel<String> roomListModel;

    static {
        chatRooms.put("lobby", ConcurrentHashMap.newKeySet());
        roomHistories.put("lobby", Collections.synchronizedList(new ArrayList<>()));
        try {
            unicastSocket = new DatagramSocket();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                new Servidor().start();
            } catch (IOException e) {
                JOptionPane.showMessageDialog(null, "Error al iniciar el servidor: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    public Servidor() throws IOException {
        fileSocket = new DatagramSocket(SERVER_PORTU);
        initializeGUI();
    }

    private void initializeGUI() {
        frame = new JFrame("Chat Servidor");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(800, 600);
        frame.setLayout(new BorderLayout());

        JPanel leftPanel = new JPanel(new BorderLayout());
        leftPanel.setPreferredSize(new Dimension(200, 600));

        roomListModel = new DefaultListModel<>();
        roomListView = new JList<>(roomListModel);
        leftPanel.add(new JLabel("Salas"), BorderLayout.NORTH);
        leftPanel.add(new JScrollPane(roomListView), BorderLayout.CENTER);

        userListModel = new DefaultListModel<>();
        userListView = new JList<>(userListModel);
        leftPanel.add(new JLabel("Usuarios"), BorderLayout.SOUTH);
        leftPanel.add(new JScrollPane(userListView), BorderLayout.SOUTH);

        frame.add(leftPanel, BorderLayout.WEST);

        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        frame.add(new JScrollPane(logArea), BorderLayout.CENTER);

        JPanel controlPanel = new JPanel(new FlowLayout());
        JButton clearLogButton = new JButton("Clear Log");
        clearLogButton.addActionListener(e -> logArea.setText(""));
        controlPanel.add(clearLogButton);

        JButton refreshButton = new JButton("Actualizar listas");
        refreshButton.addActionListener(e -> updateLists());
        controlPanel.add(refreshButton);

        frame.add(controlPanel, BorderLayout.SOUTH);

        frame.setVisible(true);
    }

    public void start() throws IOException {
        MulticastSocket serverSocket = new MulticastSocket(SERVER_PORT);
        InetAddress group = InetAddress.getByName(MULTICAST_ADDRESS);

        log("Servidor inciado! Esperando conexiones...");

        new Thread(() -> {
            try {
                while (true) {
                    byte[] buf = new byte[BUFFER_SIZE];
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    serverSocket.receive(packet);
                    new Thread(new ClientHandler(serverSocket, packet, group)).start();
                }
            } catch (IOException e) {
                log("Server error: " + e.getMessage());
            }
        }).start();
    }

    private class ClientHandler implements Runnable {
        private final MulticastSocket serverSocket;
        private final DatagramPacket receivePacket;
        private final InetAddress group;

        ClientHandler(MulticastSocket s, DatagramPacket p, InetAddress g) {
            this.serverSocket = s;
            this.receivePacket = p;
            this.group = g;
        }

        @Override
        public void run() {
            try {
                String msg = new String(receivePacket.getData(), 0, receivePacket.getLength(), StandardCharsets.UTF_8).trim();
                InetAddress addr = receivePacket.getAddress();
                int port = receivePacket.getPort();
                handleClientMessage(serverSocket, msg, addr, port, group);
            } catch (Exception e) {
                log("Error handling client: " + e.getMessage());
            }
        }
    }

    private void handleClientMessage(MulticastSocket serverSocket,
                                    String message,
                                    InetAddress clientAddress,
                                    int clientPort,
                                    InetAddress group) throws IOException, InterruptedException {
        String[] parts = message.split(":", 5);
        String action = parts[0];
        InetSocketAddress clientSock = new InetSocketAddress(clientAddress, clientPort);
        

        switch (action) {
            case "JOIN": {
                String user = parts[1];
                userList.add(user);
                userAddresses.put(user, clientSock);
                chatRooms.get("lobby").add(clientSock);
                log(user + " se unió al lobby.");
                sendUserList(serverSocket, group);

                StringBuilder sb = new StringBuilder();
                chatRooms.forEach((r, members) -> sb.append(r)
                        .append(" (")
                        .append(members.size())
                        .append(" usuarios),"));
                if (sb.length() > 0) sb.setLength(sb.length() - 1);
                sendUnicast(serverSocket, "ROOMS:" + sb, clientSock);

                updateLists();
            } break;

            case "LEAVE": {
                String user = parts[1];
                userList.remove(user);
                userAddresses.remove(user);
                chatRooms.values().forEach(set -> set.remove(clientSock));
                log(user + " disconnected.");
                broadcastMulticast(serverSocket, GREEN + user + RESET + " has left", group);
                sendUserList(serverSocket, group);
                updateLists();
            } break;

            case "ASKUSERS": {
                String user = parts[1];
                askUserList(serverSocket, user, clientSock);
            } break;

            case "CREATE_ROOM": {
                String room = parts[1];
                if (!chatRooms.containsKey(room)) {
                    chatRooms.put(room, ConcurrentHashMap.newKeySet());
                    roomHistories.put(room, Collections.synchronizedList(new ArrayList<>()));
                    broadcastMulticast(serverSocket, "ROOM_CREATED:" + room, group);
                    log("Room created: " + room);
                    updateLists();
                }
            } break;
            
            case "CHECK_ROOM": {
                String room = parts[1];
                String user = parts[2];
                InetSocketAddress client = userAddresses.get(user);

                if (chatRooms.getOrDefault(room, Collections.emptySet()).contains(client)) {
                    sendUnicast(serverSocket, "VALID_ROOM:" + room, client);
                } else {
                    sendUnicast(serverSocket, "INVALID_ROOM:" + room, client);
                    // Mover al usuario al lobby si no está en la sala
                    chatRooms.values().forEach(set -> set.remove(client));
                    chatRooms.get("lobby").add(client);
                    sendUnicast(serverSocket, "JOINED_ROOM:lobby", client);
                }
            } break;

            case "LIST_ROOMS": {
                StringBuilder sb = new StringBuilder();
                chatRooms.forEach((r, members) -> sb.append(r)
                        .append(" (")
                        .append(members.size())
                        .append(" usuarios),"));
                if (sb.length()>0) sb.setLength(sb.length()-1);
                sendUnicast(serverSocket, "ROOMS:" + sb, clientSock);
            } break;

            case "JOIN_ROOM": {
                String room = parts[1];
                String username = parts[2];

                // Mover al cliente de todas las salas a la nueva sala
                chatRooms.values().forEach(set -> set.remove(clientSock));
                chatRooms.computeIfAbsent(room, r -> ConcurrentHashMap.newKeySet())
                         .add(clientSock);

                // Actualizar dirección del usuario
                userAddresses.put(username, clientSock);

                // Enviar confirmación al cliente
                sendUnicast(serverSocket, "JOINED_ROOM:" + room, clientSock);

                // Enviar historial de la sala
                java.util.List<String> history = roomHistories.getOrDefault(room, 
                    Collections.synchronizedList(new ArrayList<>()));
                for (String msg : history) {
                    sendUnicast(serverSocket, "HISTORY:" + room + ":" + msg, clientSock);
                }

                // Actualizar listas
                sendUserList(serverSocket, group);
                updateLists();
                log(username + " se unió a: " + room);
            } break;

            case "LEAVE_ROOM": {
                String room = parts[1];
                chatRooms.getOrDefault(room, Collections.emptySet()).remove(clientSock);

                // Añadir al lobby solo si no está ya en otra sala
                if (getCurrentRoomForUser(clientSock) == null) {
                    chatRooms.get("lobby").add(clientSock);
                }

                sendUnicast(serverSocket, "LEFT_ROOM:" + room, clientSock);
                log(clientSock + " left room: " + room);
                updateLists();
            } break;

            case "MSG_ROOM": {
                String targetRoom = parts[1];
                String sender = parts[2];
                String content = parts[3];
                String time = new SimpleDateFormat("HH:mm:ss").format(new Date());
                String fullMsg = "["+time+"] " + sender + ": " + content;

                InetSocketAddress client = userAddresses.get(sender);

                // Verificación doble de membresía
                if (chatRooms.getOrDefault(targetRoom, Collections.emptySet()).contains(client)) {
                    roomHistories.computeIfAbsent(targetRoom, k -> Collections.synchronizedList(new ArrayList<>()))
                                .add(fullMsg);

                    chatRooms.get(targetRoom).forEach(dest -> {
                        sendUnicast(serverSocket, targetRoom + " | " + fullMsg, dest);
                    });
                    log(sender + " envió mensaje a " + targetRoom);
                } else {
                    // Mover al usuario al lobby y notificar
                    chatRooms.values().forEach(set -> set.remove(client));
                    chatRooms.get("lobby").add(client);
                    sendUnicast(serverSocket, "JOINED_ROOM:lobby", client);
                    sendUnicast(serverSocket, "[ERROR] Fuiste movido al lobby porque no estabas en " + targetRoom, client);
                    log(sender + " fue movido al lobby al intentar enviar a " + targetRoom);
                }
            } break;

            case "PRIVATE": {
                String from = parts[1], to = parts[2], txt = parts[3];
                String time = new SimpleDateFormat("HH:mm:ss").format(new Date());
                InetSocketAddress dest = userAddresses.get(to);
                if (dest != null) {
                    sendUnicast(serverSocket, "[PRIVATE][" + time + "] " + from + ": " + txt, dest);
                    sendUnicast(serverSocket, "[PRIVATE][" + time + "] Para " + to + ": " + txt, 
                               userAddresses.get(from));
                    log("Private message from " + from + " to " + to);
                } else {
                    sendUnicast(serverSocket, "[ERROR] Usuario " + to + " no encontrado", 
                               userAddresses.get(from));
                }
            } break;

            case "FILE": {
                String fileName = parts[1];
                int length = Integer.parseInt(parts[2]);
                receiveFile(fileSocket, clientAddress, clientPort, fileName, length);
                broadcastMulticast(serverSocket, "Archivo disponible:" + fileName, group);
                log("Archivo recibido y notificado: " + fileName);
            } break;

            default:
                log("Accion desconocida: " + action);
        }
    }

    private static void broadcastMulticast(MulticastSocket sock, String msg, InetAddress group) throws IOException {
        byte[] buf = msg.getBytes(StandardCharsets.UTF_8);
        sock.send(new DatagramPacket(buf, buf.length, group, SERVER_PORT));
        if (!msg.startsWith("ROOMS:")) {
            StringBuilder sb = new StringBuilder();
            chatRooms.forEach((r, members) -> sb.append(r).append(",")); // Solo nombres, sin conteo
            if (sb.length() > 0) sb.setLength(sb.length() - 1);
            String roomsMsg = "ROOMS:" + sb.toString();
            byte[] roomsBuf = roomsMsg.getBytes(StandardCharsets.UTF_8);
            sock.send(new DatagramPacket(roomsBuf, roomsBuf.length, group, SERVER_PORT));
        }
    }

    private void sendUnicast(DatagramSocket sock, String msg, InetSocketAddress dest) {
        try {
            byte[] buf = msg.getBytes(StandardCharsets.UTF_8);
            sock.send(new DatagramPacket(buf, buf.length, dest.getAddress(), dest.getPort()));
            log("UNICAST ➜ " + dest + " : " + msg);
        } catch (IOException e) {
            log("Error sending to " + dest + ", removing client.");
            userAddresses.values().remove(dest);
            chatRooms.values().forEach(set -> set.remove(dest));
            updateLists();
        }
    }

    private void sendUserList(MulticastSocket sock, InetAddress group) throws IOException {
        String msg = "USERS:" + String.join(",", userList);
        broadcastMulticast(sock, msg, group);
    }

    private void askUserList(DatagramSocket sock, String user, InetSocketAddress dest) throws IOException {
        String msg = "USERS:" + user + "=" + String.join(",", userList);
        byte[] buf = msg.getBytes(StandardCharsets.UTF_8);
        sock.send(new DatagramPacket(buf, buf.length, dest.getAddress(), dest.getPort()));
    }

    public static void receiveFile(DatagramSocket sock,
                                 InetAddress addr,
                                 int port,
                                 String fileName,
                                 int fileLength) throws IOException {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fileChooser.setDialogTitle("Seleccionar carpeta para guardar archivos");
        fileChooser.setCurrentDirectory(new File(DEFAULT_SERVER_FOLDER));
        
        if (fileChooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
            File dir = fileChooser.getSelectedFile();
            if (!dir.exists()) dir.mkdirs();
            
            FileOutputStream fos = new FileOutputStream(new File(dir, fileName));
            byte[] buf = new byte[BUFFER_SIZE];
            int received = 0, expectedSeq = 0;

            while (received < fileLength) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                sock.receive(p);
                int seq = byteArrayToInt(p.getData(), 0);
                if (seq == expectedSeq) {
                    fos.write(p.getData(), 4, p.getLength() - 4);
                    expectedSeq++;
                    received += p.getLength() - 4;
                }
                String ack = "ACK:" + seq;
                sock.send(new DatagramPacket(ack.getBytes(), ack.length(), addr, port));
            }
            fos.close();
        }
    }

    public static void sendFileToAll(MulticastSocket sock,
                                   String fileName,
                                   InetAddress group) throws IOException, InterruptedException {
        broadcastMulticast(sock, "FILE_HDR:" + fileName, group);
        File f = new File(DEFAULT_SERVER_FOLDER + "/" + fileName);
        FileInputStream fis = new FileInputStream(f);
        byte[] data = new byte[BUFFER_SIZE - 4];
        int seq = 0, len;

        while ((len = fis.read(data)) != -1) {
            byte[] packet = new byte[len + 4];
            addSequence(packet, seq);
            System.arraycopy(data, 0, packet, 4, len);
            sock.send(new DatagramPacket(packet, packet.length, group, SERVER_PORT));
            Thread.sleep(5);
            seq++;
        }
        broadcastMulticast(sock, "FILE_EOF:" + fileName, group);
        fis.close();
    }

    private static void addSequence(byte[] buf, int seq) {
        buf[0] = (byte)(seq >> 24);
        buf[1] = (byte)(seq >> 16);
        buf[2] = (byte)(seq >> 8);
        buf[3] = (byte)(seq);
    }

    private static int byteArrayToInt(byte[] arr, int off) {
        return ((arr[off]&0xFF)<<24) |
                ((arr[off+1]&0xFF)<<16) |
                ((arr[off+2]&0xFF)<<8) |
                (arr[off+3]&0xFF);
    }

    private void log(String message) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(message + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private void updateLists() {
        SwingUtilities.invokeLater(() -> {
            userListModel.clear();
            for (String user : userList) {
                userListModel.addElement(user);
            }

            roomListModel.clear();
            for (String room : chatRooms.keySet()) {
                roomListModel.addElement(room + " (" + chatRooms.get(room).size() + " usuarios)");
            }
        });
    }
    
    private String getCurrentRoomForUser(InetSocketAddress clientSock) {
        String nonLobbyRoom = chatRooms.entrySet().stream()
            .filter(entry -> !entry.getKey().equals("lobby") && entry.getValue().contains(clientSock))
            .map(Map.Entry::getKey)
            .findFirst()
            .orElse(null);

        return nonLobbyRoom != null ? nonLobbyRoom : 
               (chatRooms.get("lobby").contains(clientSock) ? "lobby" : null);
    }
}