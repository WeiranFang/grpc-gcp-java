/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.grpc.gcp;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;

import com.google.api.gax.longrunning.OperationFuture;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.spanner.Database;
import com.google.cloud.spanner.DatabaseAdminClient;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.DatabaseId;
import com.google.cloud.spanner.Instance;
import com.google.cloud.spanner.InstanceAdminClient;
import com.google.cloud.spanner.InstanceConfig;
import com.google.cloud.spanner.InstanceConfigId;
import com.google.cloud.spanner.InstanceId;
import com.google.cloud.spanner.InstanceInfo;
import com.google.cloud.spanner.Mutation;
import com.google.cloud.spanner.Spanner;
import com.google.cloud.spanner.SpannerExceptionFactory;
import com.google.cloud.spanner.SpannerOptions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.Empty;
import com.google.spanner.admin.database.v1.CreateDatabaseMetadata;
import com.google.spanner.admin.instance.v1.CreateInstanceMetadata;
import com.google.spanner.v1.CreateSessionRequest;
import com.google.spanner.v1.DeleteSessionRequest;
import com.google.spanner.v1.ExecuteSqlRequest;
import com.google.spanner.v1.GetSessionRequest;
import com.google.spanner.v1.ListSessionsRequest;
import com.google.spanner.v1.ListSessionsResponse;
import com.google.spanner.v1.PartialResultSet;
import com.google.spanner.v1.ResultSet;
import com.google.spanner.v1.Session;
import com.google.spanner.v1.SpannerGrpc;
import com.google.spanner.v1.SpannerGrpc.SpannerBlockingStub;
import com.google.spanner.v1.SpannerGrpc.SpannerFutureStub;
import com.google.spanner.v1.SpannerGrpc.SpannerStub;
import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.auth.MoreCallCredentials;
import io.grpc.stub.StreamObserver;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Integration tests for GcpManagedChannel with Spanner. */
@RunWith(JUnit4.class)
public final class SpannerIntegrationTest {

  private static final String GCP_PROJECT_ID = System.getenv("GCP_PROJECT_ID");
  private static final String INSTANCE_ID = "grpc-gcp-test-instance";
  private static final String DB_NAME = "grpc-gcp-test-db";

  private static final String OAUTH_SCOPE = "https://www.googleapis.com/auth/cloud-platform";
  private static final String SPANNER_TARGET = "spanner.googleapis.com";
  private static final String USERNAME = "test_user";
  private static final String DATABASE_PATH =
      String.format("projects/%s/instances/%s/databases/%s", GCP_PROJECT_ID, INSTANCE_ID, DB_NAME);
  private static final String API_FILE = "spannertest.json";

  private static final int MAX_CHANNEL = 3;
  private static final int MAX_STREAM = 2;

  private static final ManagedChannelBuilder builder =
      ManagedChannelBuilder.forAddress(SPANNER_TARGET, 443);
  private GcpManagedChannel gcpChannel;

  @BeforeClass
  public static void beforeClass() {
    Assume.assumeTrue(
        "Need to provide GCP_PROJECT_ID for SpannerIntegrationTest", GCP_PROJECT_ID != null);
    SpannerOptions options = SpannerOptions.newBuilder().setProjectId(GCP_PROJECT_ID).build();
    Spanner spanner = options.getService();
    InstanceAdminClient instanceAdminClient = spanner.getInstanceAdminClient();
    InstanceId instanceId = InstanceId.of(GCP_PROJECT_ID, INSTANCE_ID);
    initializeInstance(instanceAdminClient, instanceId);
    DatabaseAdminClient databaseAdminClient = spanner.getDatabaseAdminClient();
    DatabaseId databaseId = DatabaseId.of(instanceId, DB_NAME);
    initializeDatabase(databaseAdminClient, databaseId);
    DatabaseClient databaseClient = spanner.getDatabaseClient(databaseId);
    initializeTable(databaseClient);
  }

  private static void initializeTable(DatabaseClient databaseClient) {
    List<Mutation> mutations =
        Arrays.asList(
            Mutation.newInsertBuilder("Users")
                .set("UserId")
                .to(1)
                .set("UserName")
                .to(USERNAME)
                .build());
    databaseClient.write(mutations);
  }

  private static void initializeInstance(
      InstanceAdminClient instanceAdminClient, InstanceId instanceId) {
    InstanceConfig instanceConfig =
        Iterators.get(instanceAdminClient.listInstanceConfigs().iterateAll().iterator(), 0, null);
    checkState(instanceConfig != null, "No instance configs found");

    InstanceConfigId configId = instanceConfig.getId();
    InstanceInfo instance =
        InstanceInfo.newBuilder(instanceId)
            .setNodeCount(1)
            .setDisplayName("grpc-gcp test instance")
            .setInstanceConfigId(configId)
            .build();
    OperationFuture<Instance, CreateInstanceMetadata> op =
        instanceAdminClient.createInstance(instance);
    try {
      op.get();
    } catch (Exception e) {
      throw SpannerExceptionFactory.newSpannerException(e);
    }
  }

  private static void initializeDatabase(
      DatabaseAdminClient databaseAdminClient, DatabaseId databaseId) {
    OperationFuture<Database, CreateDatabaseMetadata> op =
        databaseAdminClient.createDatabase(
            databaseId.getInstanceId().getInstance(),
            databaseId.getDatabase(),
            Arrays.asList(
                "CREATE TABLE Users ("
                    + "  UserId INT64 NOT NULL,"
                    + "  UserName STRING(1024)"
                    + ") PRIMARY KEY (UserId)"));
    try {
      op.get();
    } catch (Exception e) {
      throw SpannerExceptionFactory.newSpannerException(e);
    }
  }

  @AfterClass
  public static void afterClass() {
    cleanupSessions();
    SpannerOptions options = SpannerOptions.newBuilder().setProjectId(GCP_PROJECT_ID).build();
    Spanner spanner = options.getService();
    InstanceAdminClient instanceAdminClient = spanner.getInstanceAdminClient();
    InstanceId instanceId = InstanceId.of(GCP_PROJECT_ID, INSTANCE_ID);
    cleanUpInstance(instanceAdminClient, instanceId);
  }

  private static void cleanupSessions() {
    ManagedChannel channel = builder.build();
    GoogleCredentials creds = getCreds();
    SpannerBlockingStub stub =
        SpannerGrpc.newBlockingStub(channel).withCallCredentials(MoreCallCredentials.from(creds));
    ListSessionsResponse responseList =
        stub.listSessions(ListSessionsRequest.newBuilder().setDatabase(DATABASE_PATH).build());
    for (Session s : responseList.getSessionsList()) {
      deleteSession(stub, s);
    }
    channel.shutdownNow();
  }

  private static void cleanUpInstance(
      InstanceAdminClient instanceAdminClient, InstanceId instanceId) {
    instanceAdminClient.deleteInstance(instanceId.getInstance());
  }

  private static GoogleCredentials getCreds() {
    GoogleCredentials creds;
    try {
      creds = GoogleCredentials.getApplicationDefault();
    } catch (Exception e) {
      return null;
    }
    ImmutableList<String> requiredScopes = ImmutableList.of(OAUTH_SCOPE);
    creds = creds.createScoped(requiredScopes);
    return creds;
  }

  /** Helper functions for BlockingStub. */
  private SpannerBlockingStub getSpannerBlockingStub() {
    GoogleCredentials creds = getCreds();
    SpannerBlockingStub stub =
        SpannerGrpc.newBlockingStub(gcpChannel)
            .withCallCredentials(MoreCallCredentials.from(creds));
    return stub;
  }

  private static void deleteSession(SpannerGrpc.SpannerBlockingStub stub, Session session) {
    if (session != null) {
      stub.deleteSession(DeleteSessionRequest.newBuilder().setName(session.getName()).build());
    }
  }

  /** Helper functions for Stub(async calls). */
  private SpannerStub getSpannerStub() {
    GoogleCredentials creds = getCreds();
    SpannerStub stub =
        SpannerGrpc.newStub(gcpChannel).withCallCredentials(MoreCallCredentials.from(creds));
    return stub;
  }

  private List<String> createAsyncSessions(SpannerStub stub) throws Exception {
    List<AsyncResponseObserver<Session>> resps = new ArrayList<>();
    List<String> respNames = new ArrayList<>();
    // Check the state of the channel first.
    assertEquals(ConnectivityState.IDLE, gcpChannel.getState(false));

    // Check CreateSession with multiple channels and streams,
    CreateSessionRequest req = CreateSessionRequest.newBuilder().setDatabase(DATABASE_PATH).build();
    for (int i = 0; i < MAX_CHANNEL * MAX_STREAM; i++) {
      AsyncResponseObserver<Session> resp = new AsyncResponseObserver<Session>();
      stub.createSession(req, resp);
      resps.add(resp);
    }
    checkChannelRefs(MAX_CHANNEL, MAX_STREAM, 0);
    for (AsyncResponseObserver<Session> resp : resps) {
      respNames.add(resp.get().getName());
    }
    // Since createSession will bind the key, check the number of keys bound with channels.
    assertEquals(ConnectivityState.READY, gcpChannel.getState(false));
    assertEquals(MAX_CHANNEL * MAX_STREAM, gcpChannel.affinityKeyToChannelRef.size());
    checkChannelRefs(MAX_CHANNEL, 0, MAX_STREAM);
    return respNames;
  }

  private void deleteAsyncSessions(SpannerStub stub, List<String> respNames) throws Exception {
    assertEquals(ConnectivityState.READY, gcpChannel.getState(false));
    for (String respName : respNames) {
      AsyncResponseObserver<Empty> resp = new AsyncResponseObserver<>();
      stub.deleteSession(DeleteSessionRequest.newBuilder().setName(respName).build(), resp);
      resp.get();
    }
    checkChannelRefs(MAX_CHANNEL, 0, 0);
    assertEquals(0, gcpChannel.affinityKeyToChannelRef.size());
  }

  /** Helper Functions for FutureStub. */
  private SpannerFutureStub getSpannerFutureStub() {
    GoogleCredentials creds = getCreds();
    SpannerFutureStub stub =
        SpannerGrpc.newFutureStub(gcpChannel).withCallCredentials(MoreCallCredentials.from(creds));
    return stub;
  }

  private List<String> createFutureSessions(SpannerFutureStub stub) throws Exception {
    List<ListenableFuture<Session>> futures = new ArrayList<>();
    List<String> futureNames = new ArrayList<>();
    assertEquals(ConnectivityState.IDLE, gcpChannel.getState(false));

    // Check CreateSession with multiple channels and streams,
    CreateSessionRequest req = CreateSessionRequest.newBuilder().setDatabase(DATABASE_PATH).build();
    for (int i = 0; i < MAX_CHANNEL * MAX_STREAM; i++) {
      ListenableFuture<Session> future = stub.createSession(req);
      futures.add(future);
    }
    checkChannelRefs(MAX_CHANNEL, MAX_STREAM, 0);
    for (ListenableFuture<Session> future : futures) {
      futureNames.add(future.get().getName());
    }
    // Since createSession will bind the key, check the number of keys bound with channels.
    assertEquals(ConnectivityState.READY, gcpChannel.getState(false));
    assertEquals(MAX_CHANNEL * MAX_STREAM, gcpChannel.affinityKeyToChannelRef.size());
    checkChannelRefs(MAX_CHANNEL, 0, MAX_STREAM);
    return futureNames;
  }

  private void deleteFutureSessions(SpannerFutureStub stub, List<String> futureNames)
      throws Exception {
    assertEquals(ConnectivityState.READY, gcpChannel.getState(false));
    for (String futureName : futureNames) {
      ListenableFuture<Empty> future =
          stub.deleteSession(DeleteSessionRequest.newBuilder().setName(futureName).build());
      future.get();
    }
    checkChannelRefs(MAX_CHANNEL, 0, 0);
    assertEquals(0, gcpChannel.affinityKeyToChannelRef.size());
  }

  /** A wrapper of checking the status of each channelRef in the gcpChannel. */
  private void checkChannelRefs(int channels, int streams, int affinities) {
    assertEquals(channels, gcpChannel.channelRefs.size());
    for (int i = 0; i < channels; i++) {
      assertEquals(streams, gcpChannel.channelRefs.get(i).getActiveStreamsCount());
      assertEquals(affinities, gcpChannel.channelRefs.get(i).getAffinityCount());
    }
  }

  @Rule public ExpectedException expectedEx = ExpectedException.none();

  @Before
  public void setupChannel() {
    File configFile =
        new File(SpannerIntegrationTest.class.getClassLoader().getResource(API_FILE).getFile());
    gcpChannel =
        (GcpManagedChannel)
            GcpManagedChannelBuilder.forDelegateBuilder(builder)
                .withApiConfigJsonFile(configFile)
                .build();
  }

  @After
  public void shutdownChannel() {
    gcpChannel.shutdownNow();
  }

  @Test
  public void testCreateAndGetSessionBlocking() throws Exception {
    SpannerBlockingStub stub = getSpannerBlockingStub();
    CreateSessionRequest req = CreateSessionRequest.newBuilder().setDatabase(DATABASE_PATH).build();
    for (int i = 0; i < MAX_CHANNEL * 2; i++) {
      Session session = stub.createSession(req);
      assertThat(session).isNotEqualTo(null);
      checkChannelRefs(1, 0, 1);

      Session responseGet =
          stub.getSession(GetSessionRequest.newBuilder().setName(session.getName()).build());
      assertEquals(responseGet.getName(), session.getName());
      checkChannelRefs(1, 0, 1);

      deleteSession(stub, session);
      checkChannelRefs(1, 0, 0);
    }
  }

  @Test
  public void testListSessionsFuture() throws Exception {
    SpannerFutureStub stub = getSpannerFutureStub();
    List<String> futureNames = createFutureSessions(stub);
    ListSessionsResponse responseList =
        stub.listSessions(ListSessionsRequest.newBuilder().setDatabase(DATABASE_PATH).build())
            .get();
    Set<String> trueNames = new HashSet<>();
    deleteFutureSessions(stub, futureNames);
  }

  @Test
  public void testListSessionsAsync() throws Exception {
    SpannerStub stub = getSpannerStub();
    List<String> respNames = createAsyncSessions(stub);
    AsyncResponseObserver<ListSessionsResponse> respList = new AsyncResponseObserver<>();
    stub.listSessions(
        ListSessionsRequest.newBuilder().setDatabase(DATABASE_PATH).build(), respList);
    ListSessionsResponse responseList = respList.get();
    deleteAsyncSessions(stub, respNames);
  }

  @Test
  public void testExecuteSqlFuture() throws Exception {
    SpannerFutureStub stub = getSpannerFutureStub();
    List<String> futureNames = createFutureSessions(stub);
    for (String futureName : futureNames) {
      ResultSet response =
          stub.executeSql(
                  ExecuteSqlRequest.newBuilder()
                      .setSession(futureName)
                      .setSql("select * FROM Users")
                      .build())
              .get();
      assertEquals(1, response.getRowsCount());
      assertEquals(USERNAME, response.getRows(0).getValuesList().get(1).getStringValue());
    }
    deleteFutureSessions(stub, futureNames);
  }

  @Test
  public void testExecuteSqlAsync() throws Exception {
    SpannerStub stub = getSpannerStub();
    List<String> respNames = createAsyncSessions(stub);
    for (String respName : respNames) {
      AsyncResponseObserver<PartialResultSet> resp = new AsyncResponseObserver<>();
      stub.executeStreamingSql(
          ExecuteSqlRequest.newBuilder().setSession(respName).setSql("select * FROM Users").build(),
          resp);
      assertEquals(USERNAME, resp.get().getValues(1).getStringValue());
    }
    deleteAsyncSessions(stub, respNames);
  }

  @Test
  public void testBoundWithInvalidAffinityKey() {
    SpannerBlockingStub stub = getSpannerBlockingStub();
    CreateSessionRequest req = CreateSessionRequest.newBuilder().setDatabase(DATABASE_PATH).build();
    Session session = stub.createSession(req);
    expectedEx.expect(StatusRuntimeException.class);
    expectedEx.expectMessage("INVALID_ARGUMENT: Invalid GetSession request.");
    stub.getSession(GetSessionRequest.newBuilder().setName("invalid_session").build());
  }

  @Test
  public void testUnbindWithInvalidAffinityKey() {
    SpannerBlockingStub stub = getSpannerBlockingStub();
    CreateSessionRequest req = CreateSessionRequest.newBuilder().setDatabase(DATABASE_PATH).build();
    Session session = stub.createSession(req);
    expectedEx.expect(StatusRuntimeException.class);
    expectedEx.expectMessage("INVALID_ARGUMENT: Invalid DeleteSession request.");
    stub.deleteSession(DeleteSessionRequest.newBuilder().setName("invalid_session").build());
  }

  @Test
  public void testBoundAfterUnbind() {
    SpannerBlockingStub stub = getSpannerBlockingStub();
    CreateSessionRequest req = CreateSessionRequest.newBuilder().setDatabase(DATABASE_PATH).build();
    Session session = stub.createSession(req);
    stub.deleteSession(DeleteSessionRequest.newBuilder().setName(session.getName()).build());
    expectedEx.expect(StatusRuntimeException.class);
    expectedEx.expectMessage("NOT_FOUND: Session not found: " + session.getName());
    stub.getSession(GetSessionRequest.newBuilder().setName(session.getName()).build());
  }

  private static class AsyncResponseObserver<RespT> implements StreamObserver<RespT> {

    private final CountDownLatch finishLatch = new CountDownLatch(1);
    private RespT response = null;

    private AsyncResponseObserver() {}

    public RespT get() throws InterruptedException, ExecutionException, TimeoutException {
      finishLatch.await(1, TimeUnit.MINUTES);
      return response;
    }

    @Override
    public void onNext(RespT response) {
      this.response = response;
    }

    @Override
    public void onError(Throwable t) {
      finishLatch.countDown();
    }

    @Override
    public void onCompleted() {
      finishLatch.countDown();
    }
  }
}
