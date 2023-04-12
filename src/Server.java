import java.io.*;
import java.net.*;
import java.util.*;

public class Server {
    private static final int PORT = 5656;
    private Map<Integer, Socket> clientSockets;

    public Server() {
        clientSockets = new HashMap<>();
    }

    public static void main(String[] args) {
        Server server = new Server();
        server.start();
    }

    public void start() {
        String serverDirectory = System.getProperty("user.dir") + "\\server-files\\";
        File file = new File(serverDirectory);
        if (!file.exists()) {
            file.mkdirs();
        }

        try {
            ServerSocket serverSocket = new ServerSocket(PORT);
            System.out.println("Server started. Listening on port " + PORT + "...");

            int currentSocketID = 1;

            while (true) {
                Socket clientSocket = serverSocket.accept();
                clientSockets.put(currentSocketID, clientSocket);
                System.out.println("New client connected: #" + currentSocketID + " (" + clientSocket.getInetAddress() + ")");

                Thread thread = new Thread(new ClientHandler(currentSocketID, this, serverSocket, serverDirectory, clientSocket));
                thread.start();

                currentSocketID++;
            }
        } catch (ConnectException e) {
            System.out.println("Could not start server.");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void removeClient(int clientSocketID) {
        clientSockets.remove(clientSocketID);
    }

    public Map<Integer, Socket> getClients() {
        return clientSockets;
    }

    public Map<Integer, Socket> getOtherClients(int currentClientSocketID) {
        Map<Integer, Socket> clients = getClients();
        clients.remove(currentClientSocketID);
        return clients;
    }
}

class ClientHandler implements Runnable {
    private int clientSocketID;
    private Server server;
    private final ServerSocket serverSocket;
    private final String serverDirectory;
    private Socket clientSocket;
    private ObjectOutputStream objectOutputStream;
    private ObjectInputStream objectInputStream;

    public ClientHandler(int clientSocketID, Server server, ServerSocket serverSocket, String serverDirectory, Socket clientSocket) throws IOException {
        this.clientSocketID = clientSocketID;
        this.server = server;
        this.serverSocket = serverSocket;
        this.serverDirectory = serverDirectory;

        this.clientSocket = clientSocket;
        this.objectOutputStream = new ObjectOutputStream(clientSocket.getOutputStream());
        this.objectInputStream = new ObjectInputStream(clientSocket.getInputStream());
    }

    public void run() {
        try {
            clientInputLoop:
            while (true) {
                try {
                    String command = (String)objectInputStream.readObject();
                    String filename = (String)objectInputStream.readObject();

                    System.out.println("Received command from client #" + clientSocketID + ": " + command);
                    switch (command) {
                        case "LIST":
                            listFiles();
                            System.out.println(" - Listed files to client #" + clientSocketID);
                            break;
                        case "UPLOAD":
                            receiveFile(filename);
                            break;
                        case "DOWNLOAD":
                            sendFile(filename);
                            System.out.println(" - Sent file '" + filename + "' to client #" + clientSocketID);
                            break;
                        case "CREATE_FOLDER":
                            createFolder(filename);
                            System.out.println(" - Created folder '" + filename + "'");
                            break;
                        case "DELETE":
                            deleteFile(filename);
                            System.out.println(" - Deleted '" + filename + "'");
                            break;
                        case "DONE":
                            System.out.println(" - File synchronization complete for client #" + clientSocketID);
                            clientSocket.close();
                            objectInputStream.close();
                            objectOutputStream.close();

                            break clientInputLoop;
                    }
                } catch (SocketException e) {
                    System.out.println("Client disconnected: #" + clientSocketID);
                    server.removeClient(clientSocketID);
                    break;
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException(e);
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void listFiles() {
        ArrayList<File> serverFiles = getFiles(new File(serverDirectory));
        for (File serverFile : serverFiles) {
            try {
                String localPath = serverFile.getAbsolutePath();
                localPath = localPath.replace(this.serverDirectory, "");

                String typePrefix = serverFile.isDirectory()
                        ? "DIR"
                        : "FILE";

                objectOutputStream.writeObject(typePrefix + " " + localPath);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        try {
            objectOutputStream.writeObject("LIST_END");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void receiveFile(String filename) throws IOException {
        FileOutputStream fileOutputStream = null;

        try {
            String filePath = serverDirectory + filename;
            fileOutputStream = new FileOutputStream(filePath);

            byte[] buffer = new byte[clientSocket.getReceiveBufferSize()];
            int bytesRead;

            while ((bytesRead = objectInputStream.read(buffer)) != -1) {
                fileOutputStream.write(buffer, 0, bytesRead);
            }

            fileOutputStream.close();

            System.out.println(" - Received file '" + filename + "' from client #" + clientSocketID);

        } catch (IOException e) {
            if (fileOutputStream != null) {
                try {
                    fileOutputStream.close();
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }

                File file = new File(serverDirectory + filename);
                if (file.exists()) {
                    if (!file.delete()) {
                        System.err.println(" - Corrupt file '" + filename + "' could not be deleted.");
                    } else {
                        System.out.println(" - Deleted corrupt filename '" + filename + "'");
                    }
                }
            }
        }


    }

    private void sendNotificationToOtherClients(String command, String filename)
    {
        Map<Integer, Socket> otherClients = server.getOtherClients(clientSocketID);
        System.out.println(" - Notifying other clients (" + otherClients.size() + ")...");

        for (Map.Entry<Integer, Socket> entry : otherClients.entrySet()) {
            Integer key = entry.getKey();
            Object otherClientSocket = entry.getValue();
            /* TODO: notify other clients to download file */
            System.out.println("#" + key.toString());
            System.out.println(otherClientSocket);
        }
    }

    private void sendFile(String filename) throws IOException {
        String filePath = serverDirectory + filename;
        FileInputStream fileInputStream = new FileInputStream(filePath);

        byte[] buffer = new byte[clientSocket.getReceiveBufferSize()];
        int bytesRead;
        while ((bytesRead = fileInputStream.read(buffer)) != -1) {
            objectOutputStream.write(buffer, 0, bytesRead);
        }

        fileInputStream.close();
        objectOutputStream.flush();

        reinitializeConnection();
    }

    private void createFolder(String folder) {
        String filePath = serverDirectory + folder;
        File file = new File(filePath);

        if (!file.exists() && !file.mkdirs()) {
            System.out.println("Failed to create folder " + folder);
        }
    }

    private void deleteFile(String filename) {
        String filePath = serverDirectory + filename;
        File file = new File(filePath);
        if (!file.delete()) {
            System.out.println("Failed to delete file " + filename);
        }
    }

    public ArrayList<File> getFiles(File directory) {
        ArrayList<File> clientFiles = new ArrayList<>();
        File[] files = directory.listFiles();

        if (files != null) {
            for (File file : files) {
                clientFiles.add(file);
                if (file.isDirectory()) {
                    clientFiles.addAll(getFiles(file));
                }
            }
        }

        Collections.sort(clientFiles);
        return clientFiles;
    }

    private void reinitializeConnection() throws IOException {
        objectOutputStream.close();
        objectInputStream.close();
        clientSocket.close();
        clientSocket = serverSocket.accept();
        objectOutputStream = new ObjectOutputStream(clientSocket.getOutputStream());
        objectInputStream = new ObjectInputStream(clientSocket.getInputStream());
    }
}