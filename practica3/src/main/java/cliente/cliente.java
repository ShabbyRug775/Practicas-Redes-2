package cliente;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.concurrent.ConcurrentHashMap;

public class Cliente {
    private static final int SERVER_PORT = 4446;
    private static final int SERVER_PORTU = 4447;
    private static final int BUFFER_SIZE = 1024;
    private static final String MULTICAST_ADDR = "230.0.0.0";
    private static String SERVER_ADDR = "localhost";
    
    java.util.List<String> userList = Collections.synchronizedList(new ArrayList<>());
    private static final Map<String, InetSocketAddress> userAddresses = new ConcurrentHashMap<>();
    private static final Map<String, Set<InetSocketAddress>> chatRooms = new ConcurrentHashMap<>();
    private static final Map<String, java.util.List<String>> roomHistories = new ConcurrentHashMap<>();

    private String username;
    private String currentRoom = "lobby";
    private AtomicBoolean awaitingFile = new AtomicBoolean(false);
    private int fileCount = 0;

    private MulticastSocket mcastSocket;
    private DatagramSocket unicastSocket;
    private InetAddress group;
    private InetAddress serverAddress;
    

    // Componentes de la interfaz
    private JFrame frame;
    private JTextArea chatArea;
    private JTextField inputField;
    private JList<String> userListView;
    private JList<String> roomList;
    private DefaultListModel<String> userListModel;
    private DefaultListModel<String> roomListModel;
    
    static {
        chatRooms.put("lobby", ConcurrentHashMap.newKeySet());
        roomHistories.put("lobby", Collections.synchronizedList(new ArrayList<>()));
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            String username = JOptionPane.showInputDialog(null, "Nombre de usuario:", "Chat Cliente", JOptionPane.PLAIN_MESSAGE);
            if (username != null && !username.trim().isEmpty()) {
                try {
                    new Cliente(username.trim()).start();
                } catch (IOException e) {
                    JOptionPane.showMessageDialog(null, "Error al iniciar el cliente: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                } catch (Exception ex) {
                    Logger.getLogger(Cliente.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        });
    }

    public Cliente(String username) throws IOException {
        this.username = username;
        
        // Pedir IP del servidor
        SERVER_ADDR = JOptionPane.showInputDialog(null, 
            "Dirección IP del servidor:", 
            "localhost");
        if (SERVER_ADDR == null || SERVER_ADDR.trim().isEmpty()) {
            SERVER_ADDR = "localhost";
        }
        
        mcastSocket = new MulticastSocket(SERVER_PORT);
        unicastSocket = new DatagramSocket();
        group = InetAddress.getByName(MULTICAST_ADDR);
        serverAddress = InetAddress.getByName(SERVER_ADDR);
        mcastSocket.joinGroup(group);

        initializeGUI();
        requestRoomList();
    }

    private void initializeGUI() {
        frame = new JFrame("Chat Cliente - " + username + " [" + SERVER_ADDR + "]");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(800, 600);
        frame.setLayout(new BorderLayout());
        
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                try {
                    if (currentRoom != null) {
                        send("LEAVE_ROOM:" + currentRoom);
                    }
                    send("LEAVE:" + username);
                } catch (IOException ex) {
                    System.err.println("Error al cerrar la conexión: " + ex.getMessage());
                } finally {
                    System.exit(0);
                }
            }
        });

        // Panel izquierdo con listas de usuarios y salas
        JPanel leftPanel = new JPanel(new BorderLayout());
        leftPanel.setPreferredSize(new Dimension(200, 600));
        
        JLabel currentRoomLabel = new JLabel("Sala actual: lobby");
        frame.add(currentRoomLabel, BorderLayout.NORTH);

        roomListModel = new DefaultListModel<>();
        roomList = new JList<>(roomListModel);
        leftPanel.add(new JLabel("Salas"), BorderLayout.NORTH);
        leftPanel.add(new JScrollPane(roomList), BorderLayout.CENTER);

        userListModel = new DefaultListModel<>();
        userListView = new JList<>(userListModel);
        leftPanel.add(new JLabel("Usuarios"), BorderLayout.SOUTH);
        leftPanel.add(new JScrollPane(userListView), BorderLayout.SOUTH);

        frame.add(leftPanel, BorderLayout.WEST);

        // Área de chat central
        chatArea = new JTextArea();
        chatArea.setEditable(false);
        chatArea.setLineWrap(true);
        chatArea.setWrapStyleWord(true);
        frame.add(new JScrollPane(chatArea), BorderLayout.CENTER);

        // Panel inferior con entrada de texto y botones
        JPanel bottomPanel = new JPanel(new BorderLayout());
        inputField = new JTextField();
        inputField.addActionListener(e -> sendMessage());
        bottomPanel.add(inputField, BorderLayout.CENTER);

        JButton sendButton = new JButton("Envíar");
        sendButton.addActionListener(e -> sendMessage());
        bottomPanel.add(sendButton, BorderLayout.EAST);

        // Panel de botones de comandos
        JPanel commandPanel = new JPanel(new FlowLayout());
        
        JButton changeServerButton = new JButton("Cambiar Servidor");
        changeServerButton.addActionListener(e -> {
            String newIp = JOptionPane.showInputDialog(frame, 
                "Nueva dirección IP del servidor:", 
                SERVER_ADDR);
            if (newIp != null && !newIp.trim().isEmpty()) {
                try {
                    SERVER_ADDR = newIp.trim();
                    serverAddress = InetAddress.getByName(SERVER_ADDR);
                    frame.setTitle("Chat Cliente - " + username + " [" + SERVER_ADDR + "]");
                    send("JOIN:" + username);
                    chatArea.append("Conectado al nuevo servidor: " + SERVER_ADDR + "\n");
                } catch (IOException ex) {
                    showError("Error al cambiar de servidor: " + ex.getMessage());
                }
            }
        });
        commandPanel.add(changeServerButton);
        
        JButton privateMessageButton = new JButton("Mensaje Privado");
        privateMessageButton.addActionListener(e -> {
            String selectedUser = userListView.getSelectedValue();
            if (selectedUser != null && !selectedUser.equals(username)) {
                String message = JOptionPane.showInputDialog(frame, "Mensaje para " + selectedUser + ":");
                if (message != null && !message.trim().isEmpty()) {
                    try {
                        send("PRIVATE:" + username + ":" + selectedUser + ":" + message);
                    } catch (IOException ex) {
                        showError("Error al enviar mensaje privado: " + ex.getMessage());
                    }
                }
            } else {
                showError("Selecciona un usuario válido para enviar mensaje privado");
            }
        });
        commandPanel.add(privateMessageButton);
        
        JButton refreshButton = new JButton("Actualizar listas");
        refreshButton.addActionListener(e -> requestRoomList());
        commandPanel.add(refreshButton);

        JButton createRoomButton = new JButton("Crear sala");
        createRoomButton.addActionListener(e -> {
            String roomName = JOptionPane.showInputDialog(frame, "Nombre de la sala:");
            if (roomName != null && !roomName.trim().isEmpty()) {
                try {
                    send("CREATE_ROOM:" + roomName.trim());
                } catch (IOException ex) {
                    showError("Error al crear la sala: " + ex.getMessage());
                }
            }
        });
        commandPanel.add(createRoomButton);

        JButton joinRoomButton = new JButton("Unirse a sala");
        joinRoomButton.addActionListener(e -> {
            String selectedRoom = roomList.getSelectedValue();
            if (selectedRoom != null) {
                String cleanRoomName = selectedRoom.split("\\s+")[0].trim();
                if (!cleanRoomName.isEmpty() && !cleanRoomName.equals(currentRoom)) {
                    try {
                        send("JOIN_ROOM:" + cleanRoomName + ":" + username);
                        // Actualizar la currentRoom del cliente inmediatamente
                        currentRoom = cleanRoomName;
                        SwingUtilities.invokeLater(() -> {
                            frame.setTitle("Chat Cliente - " + username + " (" + currentRoom + ") [" + SERVER_ADDR + "]");
                            chatArea.append("=== Intentando unirse a: " + currentRoom + " ===\n");
                        });
                    } catch (IOException ex) {
                        showError("Error al unirse a sala: " + ex.getMessage());
                    }
                } else if (cleanRoomName.equals(currentRoom)) {
                    showError("Ya estás en la sala " + currentRoom);
                }
            } else {
                showError("Por favor selecciona una sala primero");
            }
        });
        commandPanel.add(joinRoomButton);

        JButton leaveRoomButton = new JButton("Salir de la sala");
        leaveRoomButton.addActionListener(e -> {
            try {
                send("LEAVE_ROOM:" + currentRoom);
            } catch (IOException ex) {
                showError("Error al salir de la sala: " + ex.getMessage());
            }
        });
        commandPanel.add(leaveRoomButton);

        JButton sendFileButton = new JButton("Envíar archivo");
        sendFileButton.addActionListener(e -> {
            JFileChooser fileChooser = new JFileChooser();
            int returnValue = fileChooser.showOpenDialog(frame);
            if (returnValue == JFileChooser.APPROVE_OPTION) {
                File selectedFile = fileChooser.getSelectedFile();
                try {
                    send("FILE:" + selectedFile.getName() + ":0");
                    sendFile(selectedFile.getAbsolutePath());
                } catch (IOException ex) {
                    showError("Error al envíar archivo: " + ex.getMessage());
                }
            }
        });
        commandPanel.add(sendFileButton);
        
        JButton downloadButton = new JButton("Descargar archivo");
        downloadButton.addActionListener(e -> {
            String fileName = JOptionPane.showInputDialog(frame, "Ingresa el nombre de archivo a descargar:");
            if (fileName != null && !fileName.trim().isEmpty()) {
                new Thread(() -> {
                    try {
                        chatArea.append("Iniciando descarga: " + fileName + "\n");
                        receiveFile(fileName.trim());
                    } catch (IOException ex) {
                        showError("Descarga fallida: " + ex.getMessage());
                    }
                }).start();
            }
        });
        commandPanel.add(downloadButton);
        
        bottomPanel.add(commandPanel, BorderLayout.NORTH);

        frame.add(bottomPanel, BorderLayout.SOUTH);

        frame.setVisible(true);
    }

    private void sendMessage() {
        String text = inputField.getText().trim();
        if (!text.isEmpty()) {
            try {
                if (text.startsWith("/")) {
                    handle(text);
                } else {
                    // Asegurarse de que currentRoom no sea nulo/vacío,
                    // pero no forzar un JOIN_ROOM aquí
                    if (currentRoom == null || currentRoom.isEmpty()) {
                        // Este escenario idealmente no debería ocurrir si la gestión de la sala
                        // es correcta. Si ocurre, indica un problema más profundo con currentRoom no siendo configurado.
                        // Por ahora, podrías registrar un error o evitar el envío de un mensaje.
                        showError("No se puede enviar mensaje: no estas en una sala.");
                        return;
                    }
                    send("MSG_ROOM:" + currentRoom + ":" + username + ":" + text);
                }
                inputField.setText("");
            } catch (IOException e) {
                showError("Error al enviar mensaje: " + e.getMessage());
            }
        }
    }

    public void start() throws Exception {
        new Thread(() -> {
            try {
                listenMulticast();
            } catch (IOException e) {
                showError("Multicast error: " + e.getMessage());
            }
        }).start();

        new Thread(() -> {
            try {
                listenUnicast();
            } catch (IOException e) {
                showError("Unicast error: " + e.getMessage());
            }
        }).start();

        send("JOIN:" + username);
    }

    private void listenMulticast() throws IOException {
        byte[] buf = new byte[BUFFER_SIZE];
        while (true) {
            DatagramPacket pkt = new DatagramPacket(buf, buf.length);
            mcastSocket.receive(pkt);
            String msg = new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.UTF_8);

            SwingUtilities.invokeLater(() -> {
                // Modificar el método listenMulticast():
                if (msg.startsWith("ROOMS:")) {
                    String roomsStr = msg.substring(6);
                    if (!roomsStr.isEmpty()) {
                        String[] rooms = roomsStr.split(",");
                        roomListModel.clear();
                        for (String room : rooms) {
                            // Extraer solo el nombre base de la sala (eliminar conteo de usuarios)
                            String cleanRoomName = room.split("\\s+")[0].trim();
                            if (!cleanRoomName.isEmpty()) {
                                roomListModel.addElement(cleanRoomName);
                            }
                        }
                    }
                } else if (msg.startsWith("USERS:")) {
                    String[] users = msg.substring(6).split(",");
                    userListModel.clear();
                    for (String user : users) {
                        userListModel.addElement(user);
                    }
                } else if (msg.startsWith("ROOM_CREATED:")) {
                    chatArea.append("Nueva sala creada: " + msg.substring(13) + "\n");
                } else if (msg.startsWith("JOINED_ROOM:")) {
                    String newRoom = msg.substring(12);
                    if (!newRoom.equals(currentRoom)) {
                        currentRoom = newRoom;
                        chatArea.setText("");
                        frame.setTitle("Chat Cliente - " + username + " (" + currentRoom + ") [" + SERVER_ADDR + "]");
                        chatArea.append("=== Te has unido a: " + currentRoom + " ===\n");

                        try {
                            send("GET_HISTORY:" + currentRoom);
                            send("GET_USERS:" + currentRoom);
                        } catch (IOException ex) {
                            showError("Error al obtener datos de la sala: " + ex.getMessage());
                        }
                    }
                }
                else if (msg.startsWith("LEFT_ROOM:")) {
                    String leftRoom = msg.substring(10);
                    chatArea.append("Has salido de: " + leftRoom + "\n");

                    if (leftRoom.equals(currentRoom)) {
                        if (currentRoom.equals("lobby")) {
                            chatArea.append("Has abandonado el lobby\n");
                        } else {
                            currentRoom = "lobby";
                            frame.setTitle("Chat Cliente - " + username + " (" + currentRoom + ") [" + SERVER_ADDR + "]");
                            chatArea.append("Has vuelto al lobby\n");
                        }
                    }
                } else if (msg.startsWith("HISTORY:")) {
                    String[] parts = msg.split(":", 3);
                    if (parts.length == 3 && parts[1].equals(currentRoom)) {
                        chatArea.append(parts[2] + "\n");
                    }
                } else if (msg.startsWith("File available:")) {
                    String fileName = msg.substring(15).trim();
                    int option = JOptionPane.showConfirmDialog(frame, 
                        "Download file: " + fileName + "?", 
                        "File Available", 
                        JOptionPane.YES_NO_OPTION);
                    if (option == JOptionPane.YES_OPTION) {
                        new Thread(() -> {
                            try {
                                receiveFile(fileName);
                            } catch (IOException ex) {
                                showError("Download failed: " + ex.getMessage());
                            }
                        }).start();
                    }
                } else {
                    chatArea.append(msg + "\n");
                }
                chatArea.setCaretPosition(chatArea.getDocument().getLength());
            });
        }
    }

    private void listenUnicast() throws IOException {
        byte[] buf = new byte[BUFFER_SIZE];
        while (true) {
            DatagramPacket pkt = new DatagramPacket(buf, buf.length);
            unicastSocket.receive(pkt);
            String msg = new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.UTF_8);
            SwingUtilities.invokeLater(() -> {
                if (msg.startsWith("[PRIVATE]")) {
                    chatArea.setForeground(Color.BLUE);
                    chatArea.append(msg + "\n");
                    chatArea.setForeground(Color.BLACK);
                    Toolkit.getDefaultToolkit().beep();
                } else if (msg.startsWith("[ERROR]")) {
                    chatArea.setForeground(Color.RED);
                    chatArea.append(msg + "\n");
                    chatArea.setForeground(Color.BLACK);
                } else {
                    chatArea.append(msg + "\n");
                }
                chatArea.setCaretPosition(chatArea.getDocument().getLength());
            });
        }
    }

    private void handle(String in) throws IOException {
        if (in.startsWith("/create ")) {
            send("CREATE_ROOM:" + in.substring(8));
            updateLists();
        } else if (in.equals("/rooms")) {
            send("LIST_ROOMS");
            updateLists();
        } else if (in.startsWith("/join ")) {
            String roomToJoin = in.substring(6).trim(); // Recortar para eliminar espacios en blanco
            if (!roomToJoin.isEmpty() && !roomToJoin.equals(currentRoom)) {
                try {
                    send("JOIN_ROOM:" + roomToJoin + ":" + username);
                    // Actualizar la currentRoom del cliente inmediatamente después de enviar la solicitud de unión
                    // El servidor confirmará la unión, pero el cliente puede actualizar de forma optimista
                    currentRoom = roomToJoin;
                    SwingUtilities.invokeLater(() -> {
                        frame.setTitle("Chat Cliente - " + username + " (" + currentRoom + ") [" + SERVER_ADDR + "]");
                        chatArea.append("=== Intentando unirse a: " + currentRoom + " ===\n");
                    });
                } catch (IOException ex) {
                    showError("Error al unirse a sala: " + ex.getMessage());
                }
            } else if (roomToJoin.isEmpty()) {
                showError("Nombre de sala no puede estar vacío.");
            }
        } else if (in.startsWith("/exit ")) {
            send("LEAVE_ROOM:" + in.substring(6));
            updateLists();
        } else if (in.startsWith("/msg ")) {
            String[] p = in.split(" ", 3);
            if (p.length < 3) {
                showError("Usage: /msg <user> <text>");
            } else {
                send("PRIVATE:" + username + ":" + p[1] + ":" + p[2]);
            }
        } else if (in.startsWith("/file ")) {
            String fn = in.substring(6);
            send("FILE:" + fn + ":0");
            sendFile(fn);
        } else if (in.equals("/leave")) {
            send("LEAVE:" + username);
            System.exit(0);
        } else {
            send("MSG_ROOM:" + currentRoom + ":" + username + ":" + in);
        }
    }

    private void send(String msg) throws IOException {
        byte[] b = msg.getBytes(StandardCharsets.UTF_8);
        DatagramPacket p = new DatagramPacket(b, b.length, serverAddress, SERVER_PORT);
        unicastSocket.send(p);
    }

    private void sendFile(String fileName) throws IOException {
        File f = new File(fileName);
        if (!f.exists()) {
            showError("El archivo no existe");
            return;
        }
        FileInputStream fis = new FileInputStream(f);
        byte[] buf = new byte[BUFFER_SIZE - 4];
        int seq = 0, len;
        while ((len = fis.read(buf)) != -1) {
            byte[] pkt = new byte[len + 4];
            pkt[0] = (byte)(seq >> 24);
            pkt[1] = (byte)(seq >> 16);
            pkt[2] = (byte)(seq >> 8);
            pkt[3] = (byte)(seq);
            System.arraycopy(buf, 0, pkt, 4, len);
            DatagramPacket p = new DatagramPacket(pkt, pkt.length, serverAddress, SERVER_PORTU);
            unicastSocket.send(p);
            seq++;
        }
        fis.close();
        chatArea.append("File sent: " + f.getName() + "\n");
    }

    private void receiveFile(String fileName) throws IOException {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fileChooser.setDialogTitle("Seleccionar carpeta para guardar archivo");
        fileChooser.setCurrentDirectory(new File("./" + username + "_downloads"));
        
        if (fileChooser.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
            File dir = fileChooser.getSelectedFile();
            if (!dir.exists()) dir.mkdirs();

            FileOutputStream fos = new FileOutputStream(new File(dir, fileName));
            byte[] buf = new byte[BUFFER_SIZE];
            int expectedSeq = 0;
            boolean fileComplete = false;

            while (!fileComplete) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                unicastSocket.receive(p);

                String header = new String(p.getData(), 0, Math.min(p.getLength(), 20), StandardCharsets.UTF_8);
                if (header.startsWith("FILE_EOF:")) {
                    fileComplete = true;
                    String receivedFileName = header.substring(9).trim();
                    if (receivedFileName.equals(fileName)) {
                        SwingUtilities.invokeLater(() -> {
                            chatArea.append("Descarga de archivo completada: " + fileName + "\n");
                        });
                    }
                    break;
                }

                int seq = byteArrayToInt(p.getData(), 0);
                if (seq == expectedSeq) {
                    fos.write(p.getData(), 4, p.getLength() - 4);
                    expectedSeq++;

                    String ack = "ACK:" + seq;
                    byte[] ackBytes = ack.getBytes(StandardCharsets.UTF_8);
                    DatagramPacket ackPacket = new DatagramPacket(
                        ackBytes, ackBytes.length, 
                        serverAddress, SERVER_PORTU);
                    unicastSocket.send(ackPacket);
                }
            }
            fos.close();
        }
    }

    private int byteArrayToInt(byte[] arr, int off) {
        return ((arr[off]&0xFF)<<24) |
                ((arr[off+1]&0xFF)<<16) |
                ((arr[off+2]&0xFF)<<8) |
                (arr[off+3]&0xFF);
    }
    
    private void requestRoomList() {
        try {
            send("LIST_ROOMS");
        } catch (IOException ex) {
            showError("Error requesting room list: " + ex.getMessage());
        }
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
    
    // Añadir este método nuevo:
    private void validateRoomMembership() {
        try {
            send("CHECK_ROOM:" + currentRoom + ":" + username);
        } catch (IOException ex) {
            showError("Error al verificar membresía de sala: " + ex.getMessage());
        }
    }

    private void showError(String message) {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(frame, message, "Error", JOptionPane.ERROR_MESSAGE);
        });
    }
}