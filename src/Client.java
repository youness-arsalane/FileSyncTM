import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class Client {
    private static final int SERVER_PORT = 12345;

    public static void main(String[] args) throws Exception {
//        System.out.println("Enter your working directory:");
//        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
//        String clientDirectory = reader.readLine();
//
//        File clientDirectoryFile = new File(clientDirectory);
//
//        if (!clientDirectoryFile.exists()) {
//            throw new Exception("Path not found.");
//        } else if (!clientDirectoryFile.isDirectory()) {
//            throw new Exception("Path is not a directory.");
//        }
//
//        System.out.println("Path is validated.");

        String clientDirectory = "C:\\Users\\youne\\Desktop\\ClientFiles\\";

        try {
            System.out.println("Connecting to server...");
            Socket socket = new Socket("localhost", SERVER_PORT);
            System.out.println("Successfully connected to server!");

            new Thread(new ServerThread(socket, clientDirectory)).start();
        } catch (ConnectException e) {
            System.out.println("Could not connect to server.");
        }
    }
}

class ServerThread implements Runnable {
    private final String directory;
    private final ArrayList<String> clientFiles;
    private Socket clientSocket;
    private ObjectInputStream objectInputStream;
    private ObjectOutputStream objectOutputStream;

    public ServerThread(Socket clientSocket, String directory) throws IOException {
        this.clientSocket = clientSocket;
        this.directory = directory;
        this.objectOutputStream = new ObjectOutputStream(clientSocket.getOutputStream());
        this.objectInputStream = new ObjectInputStream(clientSocket.getInputStream());
        this.clientFiles = getClientFiles(new File(directory));
    }

    @Override
    public void run() {
        try {
            compareServerFiles();
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }

        while (true) {
            try {
                checkForChanges();
                TimeUnit.SECONDS.sleep(5);
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private void compareServerFiles() throws IOException, ClassNotFoundException {
        objectOutputStream.writeObject("LIST");
        objectOutputStream.writeObject("");

        ArrayList<String> filenames =  new ArrayList<>();
        while (true) {
            String filenameData = (String)objectInputStream.readObject();
            if (filenameData.equals("LIST_END")) {
                break;
            }

            filenames.add(filenameData);
        }

        for (String filenameData : filenames) {
            String[] filenameArguments = filenameData.split(" ");
            String typePrefix = filenameArguments[0];
            String filename = filenameArguments[1];

            File file = new File(directory + filename);
            if (file.exists()) {
                continue;
            }

            clientFiles.add(filename);
            switch (typePrefix) {
                case "FILE":
                    downloadFile(filename);
                    break;
                case "DIR":
                    file.mkdir();
                    break;
            }
        }
    }

    private void checkForChanges() throws IOException {
        List<String> toAdd = new ArrayList<>();
        List<String> toDelete = new ArrayList<>();

        ArrayList<String> newClientFiles = getClientFiles(new File(directory));
        clientFiles.sort(Collections.reverseOrder());

        for (String filename : clientFiles) {
            if (!newClientFiles.contains(filename)) {
                deleteFile(filename);
                toDelete.add(filename);
            }
        }

        Collections.sort(clientFiles);
        for (String filename : newClientFiles) {
            if (!clientFiles.contains(filename)) {
                if (new File(directory + filename).isDirectory()) {
                    createFolder(filename);
                } else {
                    uploadFile(filename);
                }

                toAdd.add(filename);
            }
        }

        clientFiles.addAll(toAdd);
        clientFiles.removeAll(toDelete);
        Collections.sort(clientFiles);
    }

    private void downloadFile(String filename) throws IOException, ClassNotFoundException {
        objectOutputStream.writeObject("DOWNLOAD");
        objectOutputStream.flush();
        objectOutputStream.writeObject(filename);
        objectOutputStream.flush();

        String filePath = directory + filename;
        FileOutputStream fileOutputStream = new FileOutputStream(filePath);

        byte[] buffer = new byte[clientSocket.getReceiveBufferSize()];
        int bytesRead;

        while ((bytesRead = objectInputStream.read(buffer)) != -1) {
            fileOutputStream.write(buffer, 0, bytesRead);
        }

        fileOutputStream.flush();
        fileOutputStream.close();

        reinitializeConnection();

        System.out.println("Downloaded file '" + filename + "' from server.");
    }

    private void uploadFile(String filename) throws IOException {
        objectOutputStream.writeObject("UPLOAD");
        objectOutputStream.flush();
        objectOutputStream.writeObject(filename);
        objectOutputStream.flush();

        String filePath = directory + filename;
        FileInputStream fileInputStream = new FileInputStream(filePath);

        byte[] buffer = new byte[clientSocket.getReceiveBufferSize()];
        int bytesRead;
        while ((bytesRead = fileInputStream.read(buffer)) != -1) {
            objectOutputStream.write(buffer, 0, bytesRead);
        }

        fileInputStream.close();
        objectOutputStream.flush();

        System.out.println("Uploaded file '" + filename + "' to server.");
    }

    private void createFolder(String folder) throws IOException {
        objectOutputStream.writeObject("CREATE_FOLDER");
        objectOutputStream.writeObject(folder);

        System.out.println("Created folder '" + folder + "' from client.");
    }

    private void deleteFile(String filename) throws IOException {
        objectOutputStream.writeObject("DELETE");
        objectOutputStream.writeObject(filename);

        File file = new File(directory + filename);
        file.delete();

        System.out.println("Deleted file '" + filename + "' from client.");
    }

    public ArrayList<String> getClientFiles(File directory) {
        ArrayList<String> clientFiles = new ArrayList<>();
        File[] files = directory.listFiles();

        if (files != null) {
            for (File file : files) {
                String localPath = file.getAbsolutePath();
                localPath = localPath.replace(this.directory, "");

                clientFiles.add(localPath);
                if (file.isDirectory()) {
                    clientFiles.addAll(getClientFiles(file));
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
        clientSocket = new Socket("localhost", clientSocket.getPort());
        objectOutputStream = new ObjectOutputStream(clientSocket.getOutputStream());
        objectInputStream = new ObjectInputStream(clientSocket.getInputStream());
    }
}