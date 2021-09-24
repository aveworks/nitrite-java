/*
 * Copyright (c) 2017-2020. Nitrite author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.dizitart.no2.integration;

import com.palantir.docker.compose.DockerComposeRule;
import com.palantir.docker.compose.configuration.ProjectName;
import com.palantir.docker.compose.configuration.ShutdownStrategy;
import com.palantir.docker.compose.connection.DockerPort;
import com.palantir.docker.compose.connection.waiting.HealthChecks;
import lombok.extern.slf4j.Slf4j;
import org.dizitart.no2.IntegrationTest;
import org.dizitart.no2.Nitrite;
import org.dizitart.no2.TestUtils;
import org.dizitart.no2.collection.Document;
import org.dizitart.no2.collection.NitriteCollection;
import org.dizitart.no2.filters.Filter;
import org.dizitart.no2.sync.Replica;
import org.dizitart.no2.sync.ReplicationException;
import org.dizitart.no2.sync.event.ReplicationEvent;
import org.dizitart.no2.sync.event.ReplicationEventType;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.dizitart.no2.TestUtils.createDb;
import static org.dizitart.no2.TestUtils.randomDocument;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * @author Anindya Chatterjee
 */
@Slf4j
@Category(IntegrationTest.class)
public class DataGateIntegrationTest {
    private static final int DATAGATE_PORT = 46005;
    private static final String MONGODB = "mongo";
    private static final String DATAGATE = "datagate";

    private String dbFile1, dbFile2;
    private Nitrite db1, db2;

    private DockerPort datagate;

    @Rule(order = 0)
    public Retry retry = new Retry(3);

    @Rule(order = 1)
    public DockerComposeRule dockerRule = DockerComposeRule.builder()
        .file("src/test/resources/docker-compose.yml")
        .projectName(ProjectName.random())
        .waitingForService(MONGODB, HealthChecks.toHaveAllPortsOpen())
        .waitingForService(DATAGATE, HealthChecks.toHaveAllPortsOpen())
        .shutdownStrategy(ShutdownStrategy.GRACEFUL)
        .build();

    public static String getRandomTempDbFile() {
        String dataDir = System.getProperty("java.io.tmpdir") + File.separator
            + "nitrite" + File.separator + "data";
        File file = new File(dataDir);
        if (!file.exists()) {
            assertTrue(file.mkdirs());
        }
        return file.getPath() + File.separator + UUID.randomUUID() + ".db";
    }

    @Before
    public void setUpContainer() throws Exception {
        datagate = dockerRule.containers()
            .container(DATAGATE)
            .port(DATAGATE_PORT);

        String host = datagate.getIp();
        Integer port = datagate.getExternalPort();

        UserClient.createUser(host, port, "abcd@gmail.com");
        UserClient.createUser(host, port, "abcd2@gmail.com");
        UserClient.createUser(host, port, "abcd3@gmail.com");
    }

    @After
    public void after() throws IOException, InterruptedException {
        dockerRule.dockerCompose().down();
        dockerRule.dockerCompose().kill();
        dockerRule.dockerCompose().rm();

        if (db1 != null && dbFile1 != null) {
            db1.close();
            TestUtils.deleteDb(dbFile1);
        }

        if (db2 != null && dbFile2 != null) {
            db2.close();
            TestUtils.deleteDb(dbFile2);
        }
    }

    @Test
    public void testSingleUserSingleReplica() throws Exception {
        String host = datagate.getIp();
        Integer port = datagate.getExternalPort();

        String jwt = UserClient.getToken(host, port, "abcd@gmail.com");

        db1 = createDb(dbFile1);
        NitriteCollection c1 = db1.getCollection("testSingleUserSingleReplica");

        Replica replica = Replica.builder()
            .of(c1)
            .remoteHost(host)
            .remotePort(port)
            .tenant("integration-test")
            .jwtAuth("abcd@gmail.com", jwt)
            .create();

        replica.connect();

        Random random = new Random();
        for (int i = 0; i < 10; i++) {
            Document document = randomDocument();
            c1.insert(document);
            try {
                Thread.sleep(random.nextInt(100));
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        await().atMost(5, SECONDS).until(() -> c1.size() == 10);
        c1.remove(Filter.ALL);
        await().atMost(5, SECONDS).until(() -> c1.size() == 0);
        replica.disconnectNow();
    }

    @Test
    public void testSingleUserMultiReplica() throws Exception {
        dbFile1 = getRandomTempDbFile();
        dbFile2 = getRandomTempDbFile();

        String host = datagate.getIp();
        Integer port = datagate.getExternalPort();

        db1 = createDb(dbFile1);
        db2 = createDb(dbFile2);

        NitriteCollection c1 = db1.getCollection("testSingleUserMultiReplica");
        NitriteCollection c2 = db2.getCollection("testSingleUserMultiReplica");

        String jwt = UserClient.getToken(host, port, "abcd@gmail.com");

        Replica r1 = Replica.builder()
            .of(c1)
            .remoteHost(host)
            .remotePort(port)
            .tenant("integration-test")
            .jwtAuth("abcd@gmail.com", jwt)
            .replicaName("r1")
            .acceptAllCertificates(true)
            .chunkSize(100)
            .create();

        Replica r2 = Replica.builder()
            .of(c2)
            .remoteHost(host)
            .remotePort(port)
            .tenant("integration-test")
            .jwtAuth("abcd@gmail.com", jwt)
            .replicaName("r2")
            .acceptAllCertificates(true)
            .chunkSize(100)
            .create();

        r1.connect();

        for (int i = 0; i < 10; i++) {
            Document document = randomDocument();
            c1.insert(document);
        }

        await().atMost(5, SECONDS).until(() -> c1.size() == 10);
        assertEquals(c2.size(), 0);

        r2.connect();
        await().atMost(15, SECONDS).until(() -> c2.size() == 10);

        Random random = new Random();
        for (int i = 0; i < 10; i++) {
            Document document = randomDocument();
            c1.insert(document);
            try {
                Thread.sleep(random.nextInt(100));
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        for (int i = 0; i < 20; i++) {
            Document document = randomDocument();
            c2.insert(document);
            try {
                Thread.sleep(random.nextInt(100));
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }


        await().atMost(10, SECONDS).until(() -> c1.size() == 40);
        assertEquals(c2.size(), 40);

        r1.disconnect();

        for (int i = 0; i < 10; i++) {
            Document document = randomDocument();
            c1.insert(document);
            try {
                Thread.sleep(random.nextInt(100));
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        for (int i = 0; i < 20; i++) {
            Document document = randomDocument();
            c2.insert(document);
            try {
                Thread.sleep(random.nextInt(100));
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        r1.connect();
        await().atMost(10, SECONDS).until(() -> c1.size() == 70 && c2.size() == 70);
        TestUtils.assertEquals(c1, c2);

        c2.remove(Filter.ALL);

        await().atMost(10, SECONDS).until(() -> c2.size() == 0);

        await().atMost(30, SECONDS).until(() -> c1.size() == 0);
        TestUtils.assertEquals(c1, c2);

        r1.disconnectNow();
        r2.disconnectNow();
    }

    @Test
    public void testMultiUserSingleReplica() throws Exception {
        String host = datagate.getIp();
        Integer port = datagate.getExternalPort();

        String jwt1 = UserClient.getToken(host, port, "abcd@gmail.com");
        String jwt2 = UserClient.getToken(host, port, "abcd2@gmail.com");
        String jwt3 = UserClient.getToken(host, port, "abcd3@gmail.com");

        Nitrite db1 = createDb();
        NitriteCollection c1 = db1.getCollection("testMultiUserSingleReplica");

        Nitrite db2 = createDb();
        NitriteCollection c2 = db2.getCollection("testMultiUserSingleReplica");

        Nitrite db3 = createDb();
        NitriteCollection c3 = db3.getCollection("testMultiUserSingleReplica");

        Replica r1 = Replica.builder()
            .of(c1)
            .remoteHost(host)
            .remotePort(port)
            .tenant("junit-test")
            .jwtAuth("abcd@gmail.com", jwt1)
            .create();
        r1.connect();

        Replica r2 = Replica.builder()
            .of(c2)
            .remoteHost(host)
            .remotePort(port)
            .tenant("junit-test")
            .jwtAuth("abcd2@gmail.com", jwt2)
            .create();
        r2.connect();

        Replica r3 = Replica.builder()
            .of(c3)
            .remoteHost(host)
            .remotePort(port)
            .tenant("junit-test")
            .jwtAuth("abcd3@gmail.com", jwt3)
            .create();
        r3.connect();

        for (int i = 0; i < 10; i++) {
            Document document = randomDocument();
            c1.insert(document);
        }

        for (int i = 0; i < 20; i++) {
            Document document = randomDocument();
            c2.insert(document);
        }

        for (int i = 0; i < 30; i++) {
            Document document = randomDocument();
            c3.insert(document);
        }

        await().atMost(5, SECONDS).until(() -> c1.size() == 10 && c2.size() == 20 && c3.size() == 30);

        TestUtils.assertNotEquals(c1, c2);
        TestUtils.assertNotEquals(c1, c3);
        TestUtils.assertNotEquals(c2, c3);

        r1.disconnectNow();
        r2.disconnectNow();
        r3.disconnectNow();
    }

    @Test
    public void testMultiUserMultiReplica() throws Exception {
        String host = datagate.getIp();
        Integer port = datagate.getExternalPort();

        String jwt1 = UserClient.getToken(host, port, "abcd@gmail.com");
        String jwt2 = UserClient.getToken(host, port, "abcd2@gmail.com");

        Nitrite db1 = createDb();
        NitriteCollection c1 = db1.getCollection("testMultiUserSingleReplica1");

        Nitrite db2 = createDb();
        NitriteCollection c2 = db2.getCollection("testMultiUserSingleReplica2");

        Replica r1 = Replica.builder()
            .of(c1)
            .remoteHost(host)
            .remotePort(port)
            .tenant("junit-test")
            .jwtAuth("abcd@gmail.com", jwt1)
            .create();
        r1.connect();

        Replica r2 = Replica.builder()
            .of(c2)
            .remoteHost(host)
            .remotePort(port)
            .tenant("junit-test")
            .jwtAuth("abcd2@gmail.com", jwt2)
            .create();
        r2.connect();

        for (int i = 0; i < 10; i++) {
            Document document = randomDocument();
            c1.insert(document);
        }

        for (int i = 0; i < 20; i++) {
            Document document = randomDocument();
            c2.insert(document);
        }

        await().atMost(5, SECONDS).until(() -> c1.size() == 10 && c2.size() == 20);

        TestUtils.assertNotEquals(c1, c2);
        r1.disconnectNow();
        r2.disconnectNow();
    }

    @Test
    public void testSecurityInCorrectCredentials() {
        String host = datagate.getIp();
        Integer port = datagate.getExternalPort();

        Nitrite db1 = createDb();
        NitriteCollection c1 = db1.getCollection("testSecurity");

        AtomicReference<ReplicationEvent> errorEvent = new AtomicReference<>();

        Replica r1 = Replica.builder()
            .of(c1)
            .remoteHost(host)
            .remotePort(port)
            .tenant("junit-test")
            .jwtAuth("abcd@gmail.com", "wrong_token")
            .addReplicationEventListener(event -> {
                if (event.getEventType() == ReplicationEventType.Error && errorEvent.get() == null) {
                    errorEvent.set(event);
                }
            })
            .create();
        r1.connect();

        for (int i = 0; i < 10; i++) {
            Document document = randomDocument();
            c1.insert(document);
        }

        assertEquals(c1.size(), 10);
        await().atMost(5, SECONDS).until(() -> {
            ReplicationEvent replicationEvent = errorEvent.get();
            return replicationEvent.getError() instanceof ReplicationException &&
                replicationEvent.getError().getMessage().contains("failed to validate token");
        });
        r1.disconnectNow();
    }

    @Test
    public void testCloseDbAndReconnect() throws Exception {
        String host = datagate.getIp();
        Integer port = datagate.getExternalPort();

        String jwt = UserClient.getToken(host, port, "abcd@gmail.com");

        dbFile1 = getRandomTempDbFile();

        Nitrite db = createDb(dbFile1);

        Nitrite db2 = createDb();

        NitriteCollection c1 = db.getCollection("testCloseDbAndReconnect");
        NitriteCollection c2 = db2.getCollection("testCloseDbAndReconnect");

        Replica r1 = Replica.builder()
            .of(c1)
            .remoteHost(host)
            .remotePort(port)
            .tenant("junit-test")
            .jwtAuth("abcd@gmail.com", jwt)
            .create();

        Replica r2 = Replica.builder()
            .of(c2)
            .remoteHost(host)
            .remotePort(port)
            .tenant("junit-test")
            .jwtAuth("abcd@gmail.com", jwt)
            .create();

        r1.connect();

        for (int i = 0; i < 10; i++) {
            Document document = randomDocument();
            c1.insert(document);
        }

        NitriteCollection finalC1 = c1;
        await().atMost(5, SECONDS).until(() -> finalC1.size() == 10);
        assertEquals(c2.size(), 0);

        r2.connect();
        await().atMost(5, SECONDS).until(() -> c2.size() == 10);

        Random random = new Random();
        for (int i = 0; i < 10; i++) {
            Document document = randomDocument();
            c1.insert(document);
            try {
                Thread.sleep(random.nextInt(100));
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        for (int i = 0; i < 20; i++) {
            Document document = randomDocument();
            c2.insert(document);
            try {
                Thread.sleep(random.nextInt(100));
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        NitriteCollection finalC2 = c1;
        await().atMost(10, SECONDS).until(() -> finalC2.size() == 40);
        assertEquals(c2.size(), 40);

        r1.disconnect();
        r1.close();
        db.close();

        db = createDb(dbFile1);
        c1 = db.getCollection("testCloseDbAndReconnect");
        r1 = Replica.builder()
            .of(c1)
            .remoteHost(host)
            .remotePort(port)
            .tenant("junit-test")
            .jwtAuth("abcd@gmail.com", jwt)
            .create();

        for (int i = 0; i < 10; i++) {
            Document document = randomDocument();
            c1.insert(document);
            try {
                Thread.sleep(random.nextInt(100));
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        for (int i = 0; i < 20; i++) {
            Document document = randomDocument();
            c2.insert(document);
            try {
                Thread.sleep(random.nextInt(100));
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        r1.connect();
        NitriteCollection finalC = c1;
        await().atMost(10, SECONDS).until(() -> finalC.size() == 70 && c2.size() == 70);
        TestUtils.assertEquals(c1, c2);

        c2.remove(Filter.ALL);

        await().atMost(10, SECONDS).until(() -> c2.size() == 0);
        await().atMost(5, SECONDS).until(() -> finalC.size() == 0);
        TestUtils.assertEquals(c1, c2);

        r1.disconnectNow();
        r2.disconnectNow();
    }

    @Test
    public void testDelayedConnect() throws Exception {
        String host = datagate.getIp();
        Integer port = datagate.getExternalPort();

        String jwt = UserClient.getToken(host, port, "abcd@gmail.com");

        dbFile1 = getRandomTempDbFile();

        Nitrite db1 = createDb(dbFile1);

        NitriteCollection c1 = db1.getCollection("testDelayedConnect");
        Replica r1 = Replica.builder()
            .of(c1)
            .remoteHost(host)
            .remotePort(port)
            .tenant("junit-test")
            .jwtAuth("abcd@gmail.com", jwt)
            .create();

        r1.connect();

        for (int i = 0; i < 10; i++) {
            Document document = randomDocument();
            c1.insert(document);
        }
        await().atMost(5, SECONDS).until(() -> c1.size() == 10);

        r1.disconnect();
        r1.close();
        db1.close();

        Nitrite db2 = createDb();
        NitriteCollection c2 = db2.getCollection("testDelayedConnect");
        Replica r2 = Replica.builder()
            .of(c2)
            .remoteHost(host)
            .remotePort(port)
            .tenant("junit-test")
            .jwtAuth("abcd@gmail.com", jwt)
            .create();
        r2.connect();
        await().atMost(5, SECONDS).until(() -> c2.size() == 10);

        r1.disconnectNow();
        r2.disconnectNow();
    }

    @Test
    public void testDelayedConnectRemoveAll() throws Exception {
        String host = datagate.getIp();
        Integer port = datagate.getExternalPort();

        String jwt = UserClient.getToken(host, port, "abcd@gmail.com");

        dbFile1 = getRandomTempDbFile();

        Nitrite db = createDb(dbFile1);
        NitriteCollection c1 = db.getCollection("testDelayedConnect");
        Replica r1 = Replica.builder()
            .of(c1)
            .remoteHost(host)
            .remotePort(port)
            .tenant("junit-test")
            .jwtAuth("abcd@gmail.com", jwt)
            .replicaName("r1")
            .create();

        r1.connect();

        for (int i = 0; i < 10; i++) {
            Document document = randomDocument();
            c1.insert(document);
        }

        c1.remove(Filter.ALL);
        assertEquals(c1.size(), 0);

        r1.disconnect();
        r1.close();
        db.close();

        Nitrite db2 = createDb();
        NitriteCollection c2 = db2.getCollection("testDelayedConnect");
        Replica r2 = Replica.builder()
            .of(c2)
            .remoteHost(host)
            .remotePort(port)
            .tenant("junit-test")
            .jwtAuth("abcd@gmail.com", jwt)
            .replicaName("r2")
            .create();

        r2.connect();

        for (int i = 0; i < 5; i++) {
            Document document = randomDocument();
            c2.insert(document);
        }

        db = createDb(dbFile1);
        NitriteCollection c3 = db.getCollection("testDelayedConnect");
        r1 = Replica.builder()
            .of(c3)
            .remoteHost(host)
            .remotePort(port)
            .tenant("junit-test")
            .jwtAuth("abcd@gmail.com", jwt)
            .replicaName("r1")
            .create();

        r1.connect();

        await().atMost(5, SECONDS).until(() -> {
            List<Document> l1 = c3.find().toList().stream().map(TestUtils::trimMeta).collect(Collectors.toList());
            List<Document> l2 = c2.find().toList().stream().map(TestUtils::trimMeta).collect(Collectors.toList());
            return l1.equals(l2);
        });

        r1.disconnectNow();
        r2.disconnectNow();
    }
}
