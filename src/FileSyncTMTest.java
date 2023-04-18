import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.*;

import static org.junit.jupiter.api.Assertions.*;

public class FileSyncTMTest {
    private static final String TEST_WORKING_DIR_SERVER = "unit-tests\\server\\";
    private static final String TEST_WORKING_DIR_CLIENT = "unit-tests\\client\\";
    private static final String TEST_DOWNLOAD_FILENAME = "test-download-file.txt";
    private static final String TEST_UPLOAD_FILENAME = "test-upload-file.txt";
    private Server server;
    private Client client;

    @Before
    public void setUp() throws Exception {
        new File(TEST_WORKING_DIR_SERVER).mkdirs();
        new File(TEST_WORKING_DIR_CLIENT).mkdirs();

        server = new Server();
        client = new Client();

        new Thread(() -> server.start(getServerPath())).start();
        Thread.sleep(2000);

        try {
            client.start(TEST_WORKING_DIR_CLIENT);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        Thread.sleep(2000);
    }

    private String getClientPath() {
        return System.getProperty("user.dir") + "\\" + TEST_WORKING_DIR_CLIENT;
    }

    private String getServerPath() {
        return System.getProperty("user.dir") + "\\" + TEST_WORKING_DIR_SERVER;
    }

    @Test
    public void testUploadFile() throws InterruptedException {
        Thread.sleep(2000);
        ServerThread serverThread = client.getServerThread();
        try {
            File testFile = new File(getClientPath() + TEST_UPLOAD_FILENAME);
            if (!testFile.exists()) {
                testFile.createNewFile();
            }

            serverThread.uploadFile(TEST_UPLOAD_FILENAME);
            assertTrue(true);

        } catch (Exception e) {
            System.err.println(e.getMessage());
            assertTrue(false);
        }
    }

    @Test
    public void testDownloadFile() throws InterruptedException {
        Thread.sleep(2000);
        ServerThread serverThread = client.getServerThread();
        try {
            File testFile = new File(getServerPath() + TEST_DOWNLOAD_FILENAME);
            if (!testFile.exists()) {
                testFile.createNewFile();
            }

            serverThread.downloadFile(TEST_DOWNLOAD_FILENAME);
            assertTrue(true);

        } catch (Exception e) {
            System.err.println(e.getMessage());
            assertTrue(false);
        }
    }

    @After
    public void tearDown() throws IOException, InterruptedException {
        Thread.sleep(2000);
        client.stop();
        server.stop();

        new File(TEST_WORKING_DIR_CLIENT + TEST_DOWNLOAD_FILENAME).delete();
        new File(TEST_WORKING_DIR_CLIENT + TEST_UPLOAD_FILENAME).delete();
        new File(TEST_WORKING_DIR_CLIENT).delete();

        new File(TEST_WORKING_DIR_SERVER + TEST_DOWNLOAD_FILENAME).delete();
        new File(TEST_WORKING_DIR_SERVER + TEST_UPLOAD_FILENAME).delete();
        new File(TEST_WORKING_DIR_SERVER).delete();
    }
}