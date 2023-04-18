import java.io.*;
import java.net.*;
import java.util.*;

public class Server {
    private static final int PORT = 5656;
    private final Map<Integer, ClientSocketObject> clientSockets;
    private ServerSocket serverSocket;

    public Server() {
        clientSockets = new HashMap<>();
    }

    public static void main(String[] args) {
        Server server = new Server();
        server.start();
    }

    public void start() {
        String serverDirectory = System.getProperty("user.dir") + "\\server-files\\";
        start(serverDirectory);
    }

    public void start(String serverDirectory) {
        File file = new File(serverDirectory);
        if (!file.exists()) {
            file.mkdirs();
        }

        try {
            serverSocket = new ServerSocket(PORT);
            System.out.println("Server started. Listening on port " + PORT + "...");

            int currentSocketID = 1;

            while (true) {
                Socket socket = serverSocket.accept();
                ClientSocketObject clientSocketObject = new ClientSocketObject(currentSocketID, socket);

                clientSockets.put(clientSocketObject.getId(), clientSocketObject);
                System.out.println("New client connected: #" + clientSocketObject.getId() + " (" + socket.getInetAddress() + ")");

                Thread thread = new Thread(new ClientHandler(clientSocketObject, this, serverDirectory));
                thread.start();

                currentSocketID++;
            }
        } catch (ConnectException e) {
            System.out.println("Could not start server.");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void stop() throws IOException {
        if (serverSocket != null) {
            serverSocket.close();
        }
    }

    public Map<Integer, ClientSocketObject> getClients() {
        return clientSockets;
    }

    public Map<Integer, ClientSocketObject> getOtherClients(int currentClientSocketID) {
        Map<Integer, ClientSocketObject> otherClients = new HashMap<>();

        for (Map.Entry<Integer, ClientSocketObject> entry : getClients().entrySet()) {
            Integer key = entry.getKey();
            if (key == currentClientSocketID) {
                continue;
            }

            otherClients.put(key, entry.getValue());
        }

        return otherClients;
    }

    public void removeClient(int clientSocketID) {
        clientSockets.remove(clientSocketID);
    }
}

class ClientHandler implements Runnable {
    private final ClientSocketObject clientSocketObject;
    private final Server server;
    private final String serverDirectory;
    private final Socket clientSocket;
    private final ObjectOutputStream objectOutputStream;
    private final ObjectInputStream objectInputStream;

    public ClientHandler(ClientSocketObject clientSocketObject, Server server, String serverDirectory) throws IOException {
        this.clientSocketObject = clientSocketObject;
        this.server = server;
        this.serverDirectory = serverDirectory;
        this.clientSocket = clientSocketObject.getSocket();
        this.objectOutputStream = new ObjectOutputStream(clientSocket.getOutputStream());
        this.objectInputStream = new ObjectInputStream(clientSocket.getInputStream());

        clientSocketObject.setObjectOutputStream(objectOutputStream);
        clientSocketObject.setObjectInputStream(objectInputStream);
    }

    public void run() {
        try {
            while (true) {
                try {
                    String command = (String) objectInputStream.readObject();
                    String filename = (String) objectInputStream.readObject();

                    printClientEvent("Received command: " + command, false);
                    switch (command) {
                        case "LIST":
                            listFiles();
                            printClientEvent("Listed files", false);
                            break;
                        case "STACKED_CHANGES":
                            listStackedChanges();
                            printClientEvent("Sent stacked changes", false);
                            break;
                        case "UPLOAD":
                            receiveFile(filename);
                            break;
                        case "DOWNLOAD":
                            sendFile(filename);
                            printClientEvent("Sent file: '" + filename + "'", false);
                            break;
                        case "CREATE_FOLDER":
                            createFolder(filename);
                            printClientEvent("Created folder: '" + filename + "'", false);
                            break;
                        case "DELETE":
                            deleteFile(filename);
                            printClientEvent("Deleted: '" + filename + "'", false);
                            break;
                        case "EXISTS":
                            checkExistence(filename);
                            printClientEvent("Checked existence: '" + filename + "'", false);
                            break;
                    }
                } catch (SocketException e) {
                    server.removeClient(clientSocketObject.getId());
                    printClientEvent("Disconnected", false);
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

    private void listStackedChanges() {
        ArrayList<String> stackedChanges = clientSocketObject.getStackedChanges();
        for (String stackedChange : stackedChanges) {
            try {
                objectOutputStream.writeObject(stackedChange);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        try {
            objectOutputStream.writeObject("STACKED_CHANGES_END");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        clientSocketObject.emptyStackedChanges();
    }

    private void receiveFile(String filename) throws IOException {
        printClientEvent("Receiving file '" + filename + "'...", false);
        FileOutputStream fileOutputStream = null;

        try {
            String filePath = serverDirectory + filename;
            fileOutputStream = new FileOutputStream(filePath);

            byte[] buffer = new byte[clientSocket.getReceiveBufferSize()];
            int bytesRead;

            long size = objectInputStream.readLong();
            while (size > 0 && (bytesRead = objectInputStream.read(buffer, 0, (int) Math.min(buffer.length, size))) != -1) {
                fileOutputStream.write(buffer, 0, bytesRead);
                size -= bytesRead;
            }

            fileOutputStream.close();

            printClientEvent("Received file '" + filename + "'", false);

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
                        printClientEvent("Corrupt file '" + filename + "' could not be deleted.", true);
                    } else {
                        printClientEvent("Deleted corrupt filename '" + filename + "'", false);
                    }
                }
            }
        }

        appendToStackedChange("DOWNLOAD", filename);
    }

    private void appendToStackedChange(String command, String filename) {
        Map<Integer, ClientSocketObject> otherClients = server.getOtherClients(clientSocketObject.getId());
        System.out.println(" - Total clients connected (" + server.getClients().size() + ")...");
        System.out.println(" - Adding to stacked changes (" + otherClients.size() + ")...");

        for (Map.Entry<Integer, ClientSocketObject> entry : otherClients.entrySet()) {
            ClientSocketObject otherClientSocket = entry.getValue();

            String stackedChange = command + " " + filename;
            otherClientSocket.appendStackedChange(stackedChange);
            System.out.println("Client #" + otherClientSocket.getId() + " - appended stacked change (" + stackedChange + ")");
        }
    }

    private void sendFile(String filename) throws IOException {
        clientSocketObject.setBusy(true);

        String filePath = serverDirectory + filename;
        FileInputStream fileInputStream = new FileInputStream(filePath);

        byte[] buffer = new byte[clientSocket.getReceiveBufferSize()];
        int bytesRead;

        File file = new File(filePath);
        objectOutputStream.writeLong(file.length());

        while ((bytesRead = fileInputStream.read(buffer)) != -1) {
            objectOutputStream.write(buffer, 0, bytesRead);
        }

        fileInputStream.close();
        objectOutputStream.flush();

        clientSocketObject.setBusy(false);
    }

    private void createFolder(String folder) {
        String filePath = serverDirectory + folder;
        File file = new File(filePath);

        if (!file.exists() && !file.mkdirs()) {
            System.out.println("Failed to create folder " + folder);
        }

        appendToStackedChange("CREATE_FOLDER", folder);
    }

    private void deleteFile(String filename) {
        String filePath = serverDirectory + filename;
        File file = new File(filePath);
        if (!file.delete()) {
            System.out.println("Failed to delete file " + filename);
        }

        appendToStackedChange("DELETE", filename);
    }

    private void checkExistence(String filename) throws IOException {
        String filePath = serverDirectory + filename;
        File file = new File(filePath);

        objectOutputStream.writeBoolean(file.exists());
        objectOutputStream.flush();
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

    private void printClientEvent(String event, boolean isError) {
        if (isError) {
            System.err.println("Client #" + clientSocketObject.getId() + " - " + event);
        } else {
            System.out.println("Client #" + clientSocketObject.getId() + " - " + event);
        }
    }
}

class ClientSocketObject {
    private final int id;
    private final Socket socket;
    private ObjectOutputStream objectOutputStream;
    private ObjectInputStream objectInputStream;
    private ArrayList<String> stackedChanges;
    private boolean isBusy = false;

    public ClientSocketObject(int id, Socket socket) throws IOException {
        this.id = id;
        this.socket = socket;
        this.stackedChanges = new ArrayList<>();
    }

    public int getId() {
        return id;
    }

    public Socket getSocket() {
        return socket;
    }

    public ObjectOutputStream getObjectOutputStream() {
        return objectOutputStream;
    }

    public void setObjectOutputStream(ObjectOutputStream objectOutputStream) {
        this.objectOutputStream = objectOutputStream;
    }

    public ObjectInputStream getObjectInputStream() {
        return objectInputStream;
    }

    public void setObjectInputStream(ObjectInputStream objectInputStream) {
        this.objectInputStream = objectInputStream;
    }

    public ArrayList<String> getStackedChanges() {
        return stackedChanges;
    }

    public void setStackedChanges(ArrayList<String> stackedChanges) {
        this.stackedChanges = stackedChanges;
    }

    public void appendStackedChange(String stackedChange) {
        this.stackedChanges.add(stackedChange);
    }

    public void emptyStackedChanges() {
        this.stackedChanges.clear();
    }

    public boolean isBusy() {
        return isBusy;
    }

    public void setBusy(boolean busy) {
        isBusy = busy;
    }
}