package org.tron.p2p.discover.protocol.kad;

import org.tron.p2p.config.Parameter;
import org.tron.p2p.discover.DiscoverService;
import org.tron.p2p.discover.Node;
import org.tron.p2p.discover.protocol.kad.table.NodeTable;
import org.tron.p2p.discover.socket.UdpEvent;
import org.tron.p2p.discover.socket.message.DiscoverMessageInspector;
import org.tron.p2p.discover.socket.message.FindNodeMessage;
import org.tron.p2p.discover.socket.message.Message;
import org.tron.p2p.discover.socket.message.NeighborsMessage;
import org.tron.p2p.discover.socket.message.PingMessage;
import org.tron.p2p.discover.socket.message.PongMessage;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

public class KadService implements DiscoverService {

  private static final int MAX_NODES = 2000;
  private static final int NODES_TRIM_THRESHOLD = 3000;

  private List<Node> bootNodes = new ArrayList<>();

  private volatile boolean inited = false;

  private Map<String, NodeHandler> nodeHandlerMap = new ConcurrentHashMap<>();

  private Consumer<UdpEvent> messageSender;

  private NodeTable table;
  private Node homeNode;

  private ScheduledExecutorService pongTimer;
  private DiscoverTask discoverTask;

  public void init() {
    for (InetSocketAddress boot : Parameter.p2pConfig.getSeedNodes()) {
      bootNodes.add(Node.instanceOf(boot.toString()));
    }
    this.pongTimer = Executors.newSingleThreadScheduledExecutor();
    this.homeNode = new Node(Node.getNodeId(), Parameter.p2pConfig.getIp(),
        Parameter.p2pConfig.getPort());
    this.table = new NodeTable(homeNode);
    discoverTask = new DiscoverTask(this);
    discoverTask.init();
  }

  public void close() {
    try {
      //nodeManagerTasksTimer.cancel();
      pongTimer.shutdownNow();
      discoverTask.close();
    } catch (Exception e) {
      //logger.error("Close nodeManagerTasksTimer or pongTimer failed", e);
      throw e;
    }
  }

  public void updateNode(Node node) {
    getNodeHandler(node);
  }

  public List<Node> getConnectableNodes() {
    return null;
  }

  public List<Node> getTableNodes() {
    return null;
  }

  public List<Node> getAllNodes() {
    return null;
  }

  @Override
  public void setMessageSender(Consumer<UdpEvent> messageSender) {
    this.messageSender = messageSender;
  }

  @Override
  public void channelActivated() {
    if (!inited) {
      inited = true;

      for (Node node : bootNodes) {
        getNodeHandler(node);
      }
    }
  }

  @Override
  public void handleEvent(UdpEvent udpEvent) {
    Message m = udpEvent.getMessage();
    if (!DiscoverMessageInspector.valid(m)) {
      return;
    }

    InetSocketAddress sender = udpEvent.getAddress();

    Node n = new Node(m.getFrom().getId(), sender.getHostString(), sender.getPort(),
        m.getFrom().getPort());

    NodeHandler nodeHandler = getNodeHandler(n);
    nodeHandler.getNode().setId(n.getId());
    nodeHandler.getNode().touch();
    //nodeHandler.getNodeStatistics().messageStatistics.addUdpInMessage(m.getType());
    //int length = udpEvent.getMessage().getData().length + 1;
    //MetricsUtil.meterMark(MetricsKey.NET_UDP_IN_TRAFFIC, length);
    //Metrics.histogramObserve(MetricKeys.Histogram.UDP_BYTES, length,
    //    MetricLabels.Histogram.TRAFFIC_IN);

    switch (m.getType()) {
      case DISCOVER_PING:
        nodeHandler.handlePing((PingMessage) m);
        break;
      case DISCOVER_PONG:
        nodeHandler.handlePong((PongMessage) m);
        break;
      case DISCOVER_FIND_NODE:
        nodeHandler.handleFindNode((FindNodeMessage) m);
        break;
      case DISCOVER_NEIGHBORS:
        nodeHandler.handleNeighbours((NeighborsMessage) m);
        break;
      default:
        break;
    }
  }

  public NodeHandler getNodeHandler(Node n) {
    String key = getKey(n);
    NodeHandler ret = nodeHandlerMap.get(key);
    if (ret == null) {
      trimTable();
      ret = new NodeHandler(n, this);
      nodeHandlerMap.put(key, ret);
    }
    return ret;
  }

  public NodeTable getTable() {
    return table;
  }

  public Node getPublicHomeNode() {
    return homeNode;
  }

  public void sendOutbound(UdpEvent udpEvent) {
    if (Parameter.p2pConfig.isDiscoverEnable() && messageSender != null) {
      messageSender.accept(udpEvent);
//      int length = udpEvent.getMessage().getSendData().length;
//      MetricsUtil.meterMark(MetricsKey.NET_UDP_OUT_TRAFFIC, length);
//      Metrics.histogramObserve(MetricKeys.Histogram.UDP_BYTES, length,
//          MetricLabels.Histogram.TRAFFIC_OUT);

    }
  }

  public ScheduledExecutorService getPongTimer() {
    return pongTimer;
  }

  private void trimTable() {
    if (nodeHandlerMap.size() > NODES_TRIM_THRESHOLD) {
      nodeHandlerMap.values().forEach(handler -> {
        if (!handler.getNode().isConnectible(Parameter.p2pConfig.getVersion())) {
          nodeHandlerMap.values().remove(handler);
        }
      });
    }
    if (nodeHandlerMap.size() > NODES_TRIM_THRESHOLD) {
      List<NodeHandler> sorted = new ArrayList<>(nodeHandlerMap.values());
      // todo
      //sorted.sort(Comparator.comparingInt(o -> o.getNodeStatistics().getReputation()));
      for (NodeHandler handler : sorted) {
        nodeHandlerMap.values().remove(handler);
        if (nodeHandlerMap.size() <= MAX_NODES) {
          break;
        }
      }
    }
  }

  private String getKey(Node n) {
    return getKey(new InetSocketAddress(n.getHost(), n.getPort()));
  }

  private String getKey(InetSocketAddress address) {
    InetAddress inetAddress = address.getAddress();
    return (inetAddress == null ? address.getHostString() : inetAddress.getHostAddress()) + ":"
        + address.getPort();
  }
}
