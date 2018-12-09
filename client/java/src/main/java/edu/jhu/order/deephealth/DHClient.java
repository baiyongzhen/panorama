package edu.jhu.order.deephealth;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.grpc.Status;
import com.google.protobuf.Message;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.logging.ConsoleHandler;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import edu.jhu.order.deephealth.Health;
import edu.jhu.order.deephealth.Health.Observation;
import edu.jhu.order.deephealth.Health.Report;
import edu.jhu.order.deephealth.DHBuffer.AggregateValue;
import edu.jhu.order.deephealth.Health.Metric;
import edu.jhu.order.deephealth.HealthServiceGrpc.HealthServiceBlockingStub;
import edu.jhu.order.deephealth.HealthServiceGrpc.HealthServiceFutureStub;
import edu.jhu.order.deephealth.HealthServiceGrpc.HealthServiceStub;
import edu.jhu.order.deephealth.Service.GetReportRequest;
import edu.jhu.order.deephealth.Service.ObserveReply;
import edu.jhu.order.deephealth.Service.ObserveRequest;
import edu.jhu.order.deephealth.Service.SubmitReportReply;
import edu.jhu.order.deephealth.Service.SubmitReportRequest;
import edu.jhu.order.deephealth.Service.PingRequest;
import edu.jhu.order.deephealth.Service.PingReply;
import edu.jhu.order.deephealth.Service.RegisterRequest;
import edu.jhu.order.deephealth.Service.RegisterReply;
import edu.jhu.order.deephealth.Service.Peer;

import com.google.protobuf.util.Timestamps;

public class DHClient
{
	private static final Logger logger;
  static {
    Logger mainLogger = Logger.getLogger("edu.jhu.order.deephealth");
    mainLogger.setUseParentHandlers(false);
    ConsoleHandler handler = new ConsoleHandler();
    handler.setFormatter(new SimpleFormatter() {
      private static final String format = "%1$tF %1$tH:%1$tM:%1$tS,%1$tL - %2$-5s [%3$s] %4$s%n";
      @Override
      public synchronized String format(LogRecord lr) {
        return String.format(format,
            new Date(lr.getMillis()),
            lr.getLevel().getLocalizedName(),
            lr.getLoggerName(),
            lr.getMessage()
            );
      }
    });
    mainLogger.addHandler(handler);
    logger = Logger.getLogger(DHClient.class.getName());
  }

  public static final String CONFIG_FILE = "dh.cfg";

  private String module;
  private String id;
  private String serverAddr;
  private int serverPort;
  private long handle;

  private ManagedChannel channel;
  private HealthServiceBlockingStub blockingStub;
  private HealthServiceStub asyncStub;
  private HealthServiceFutureStub futureStub;
  private ExecutorService futureExecutor;
  private boolean ready = false;

  private DHRateLimiter rateLimiter;
  private DHResolver resolver;
  private DHRequestProcessor processor;
  private DHPendingTracker tracker;

  private static DHClient instance = null;

  public static DHClient getInstance() {
    if (instance == null) {
      instance = new DHClient();
    }
    return instance;
  }

  private DHClient()
  {
    rateLimiter = new DHRateLimiter();
    resolver = new DHResolver();
    processor = new DHRequestProcessor(rateLimiter, resolver, this);
    tracker = new DHPendingTracker(processor, DHConfig.DEFAULT_EXPIRE_MS);
  }

  public boolean init(String module, String id)
  {
    DHConfig config = DHConfig.parse(CONFIG_FILE);
    if (config == null) {
      try {
        String hostname = InetAddress.getLocalHost().getHostName().split("\\.")[0];
        return init(hostname, DHConfig.DEFAULT_PORT, module, id);
      } catch (UnknownHostException e) {
        logger.warning("Failed to infer host name: " + e);
        return false;
      }
    } else {
      tracker.setExpirationInterval(config.getExpireMs());
      return init(config.getDHServerAddr(), config.getDHServerPort(), module, id);
    }
  }

  public boolean init(String addr, int port, String module, String id)
  {
    if (ready)
      return true;

    this.serverAddr = addr;
    this.serverPort = port;
    this.module = module;
    this.id = id;

    logger.info("Attempting to connect to " + addr + ":" + port + " by module " + module + " id " + id);

    ManagedChannelBuilder<?> channelBuilder = ManagedChannelBuilder.forAddress(
        serverAddr, serverPort).usePlaintext(true);
    this.channel = channelBuilder.build();
    this.blockingStub = HealthServiceGrpc.newBlockingStub(channel);
    this.asyncStub = HealthServiceGrpc.newStub(channel);
    this.futureStub = HealthServiceGrpc.newFutureStub(channel);
    this.futureExecutor = Executors.newFixedThreadPool(2);

    RegisterRequest request = RegisterRequest.newBuilder().setModule(this.module).setObserver(id).build();
    RegisterReply reply;
    try {
      reply = blockingStub.register(request);
    } catch (StatusRuntimeException e) {
      logger.warning("Register RPC failed: " + e.getStatus());
      return false;
    }
    handle = reply.getHandle();
    logger.info("Got register reply with handle " + handle);
    processor.start();
    tracker.start();
    this.ready = true;
    return true;
  }

  public String getServerAddr()
  {
    return serverAddr;
  }

  public int getServerPort()
  {
    return serverPort;
  }

  public String getId()
  {
    return id;
  }

  public void setId(String ID)
  {
    id = ID;
  }

  public void mapSubject(String subject, InetSocketAddress address)
  {
    resolver.map(subject, address);
  }

  public void shutdown() throws InterruptedException 
  {
    if (!ready)
      return;
    tracker.shutdown();
    processor.shutdown();
    channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    ready = false;
  }

  public HealthServiceBlockingStub block()
  {
    return blockingStub;
  }

  public HealthServiceStub async()
  {
    return asyncStub;
  }

  public HealthServiceFutureStub future()
  {
    return futureStub;
  }

  public boolean observe(String subject)
  {
    if (!ready)
      return false;
    logger.info("Start observing " + subject);
    ObserveRequest request = ObserveRequest.newBuilder().setSubject(subject).build();
    ObserveReply reply;
    try {
      reply = blockingStub.observe(request);
    } catch (StatusRuntimeException e) {
      logger.severe("Observe RPC failed: " + e.getStatus());
      return false;
    }
    boolean ok = reply.getSuccess();
    logger.info("Result: " + ok);
    return ok;
  }

  public boolean stopObserving(String subject)
  {
    if (!ready)
      return false;
    logger.info("Stop observing " + subject);
    ObserveRequest request = ObserveRequest.newBuilder().setSubject(subject).build();
    ObserveReply reply; 
    try {
      reply = blockingStub.stopObserving(request);
    } catch (StatusRuntimeException e) {
      logger.severe("StopObserving RPC failed: " + e.getStatus());
      return false;
    }
    boolean ok = reply.getSuccess();
    logger.info("Result: " + ok);
    return ok;
  }

  private static final StreamObserver<SubmitReportReply> gEmptySubmityResponseObserver = new StreamObserver<SubmitReportReply>() {
      @Override
      public void onNext(SubmitReportReply reply) {
        logger.info("Got one reply for an async submission: " + reply);
      }
      @Override
      public void onError(Throwable t) {
        logger.info("Async submit report error: " + t);
      }
      @Override
      public void onCompleted() {
        logger.info("Async submit completed");
      }
  };

  private static final FutureCallback<SubmitReportReply> gDefaultSubmitResponseCb = 
    new FutureCallback<SubmitReportReply>() {
      @Override
      public void onSuccess(SubmitReportReply reply) {
        SubmitReportReply.Status status = reply.getResult();
        logger.info("Got async submit report reply: " + status);
      }

      @Override
      public void onFailure(Throwable t) {
        Status status = Status.fromThrowable(t);
        logger.severe("Failed to async submit report: " + status.getDescription());
      }
    };

  public void selfInform(String name, Health.Status status, float score)
  {
    if (!ready)
      return;
    processor.add(id, name, status, score, false, false);
  }

  public void selfInformAsync(String name, Health.Status status, float score)
  {
    if (!ready)
      return;
    processor.add(id, name, status, score, false, true);
  }

  public void selfReqPending(String name, String reqId, float score)
  {
    if (!ready)
      return;
    tracker.add(id, name, reqId, score, false);
  }

  public void selfReqClear(String name, String reqId, float score)
  {
    if (!ready)
      return;
    tracker.clear(id, name, reqId, score, false);
  }

  public void reqPending(String subject, String name, String id, float score)
  {
    if (!ready)
      return;
    tracker.add(subject, name, id, score, true);
  }

  public void reqClear(String subject, String name, String id, float score)
  {
    if (!ready)
      return;
    tracker.clear(subject, name, id, score, true);
  }

  public void reqClearFail(String subject, String name, String id, float score)
  {
    if (!ready)
      return;
    tracker.clearFail(subject, name, id, score, true);
  }

  public void inform(String subject, String name, Health.Status status, float score, boolean resolve)
  {
    if (!ready)
      return;
    processor.add(subject, name, status, score, resolve, false);
  }

  public void informAsync(String subject, String name, Health.Status status, float score, boolean resolve)
  {
    if (!ready)
      return;
    processor.add(subject, name, status, score, resolve, true);
  }

  public SubmitReportReply.Status selfReport(long time, Metric... metrics)
  {
    return report(time, id, metrics);
  }

  public SubmitReportReply.Status report(long time, String subject, Metric... metrics)
  {
    if (!ready)
      return null;
    logger.info("Submitting report about " + subject + " at " + time);
    Observation observation = DHBuilder.NewObservation(time, metrics);
    Report report = Report.newBuilder().setObserver(id).setSubject(subject).setObservation(observation).build();
    SubmitReportRequest request = SubmitReportRequest.newBuilder().setHandle(handle).setReport(report).build();
    SubmitReportReply reply; 
    try {
      reply = blockingStub.submitReport(request);
    } catch (StatusRuntimeException e) {
      logger.severe("SubmitReport RPC failed: " + e.getStatus());
      return null;
    }
    SubmitReportReply.Status status = reply.getResult();
    logger.info("Result: " + status);
    return status;
  }

  public void reportAsync(long time, final FutureCallback<SubmitReportReply> cb, 
      String subject, Metric... metrics) 
  {
    if (!ready)
      return;
    logger.info("Asynchronously submitting report about " + subject + " at " + time);
    Observation observation = DHBuilder.NewObservation(time, metrics);
    Report report = Report.newBuilder().setObserver(id).setSubject(subject).setObservation(observation).build();
    SubmitReportRequest request = SubmitReportRequest.newBuilder().setHandle(handle).setReport(report).build();

    ListenableFuture<SubmitReportReply> response = futureStub.submitReport(request);
    if (cb == null) {
      Futures.addCallback(response, gDefaultSubmitResponseCb, futureExecutor);
    } else {
      Futures.addCallback(response, cb, futureExecutor);
    }
  }

  public Report getReport(String subject)
  {
    if (!ready)
      return null;
    logger.info("Getting report for " + subject);
    GetReportRequest request = GetReportRequest.newBuilder().setSubject(subject).build();
    Report report;
    try {
      report = blockingStub.getLatestReport(request);
    } catch (StatusRuntimeException e) {
      logger.severe("GetLatestReport RPC failed: " + e.getStatus());
      return null;
    }
    logger.info("Result: " + report);
    return report;
  }

  // ping local health server
  public long ping()
  {
    if (!ready)
      return -1;
    long timeMillis = System.currentTimeMillis();
    Peer source = Peer.newBuilder().setId(id).setAddr("localhost").build();
    PingRequest request = PingRequest.newBuilder().setSource(source).setTime(Timestamps.fromMillis(timeMillis)).build();
    PingReply reply;
    try {
      reply = blockingStub.ping(request);
    } catch (StatusRuntimeException e) {
      logger.warning("Ping RPC failed: " + e.getStatus());
      return -1;
    }
    long result = Timestamps.toMillis(reply.getTime());
    Date date = new Date(result);
    DateFormat formatter = new SimpleDateFormat("HH:mm:ss:SSS");
    logger.info("Got ping reply with time: " + formatter.format(date));
    return result;
  }
}
