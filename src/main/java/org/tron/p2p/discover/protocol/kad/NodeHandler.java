package org.tron.p2p.discover.protocol.kad;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.tron.p2p.base.Parameter;
import org.tron.p2p.discover.Node;
import org.tron.p2p.discover.message.kad.FindNodeMessage;
import org.tron.p2p.discover.message.kad.NeighborsMessage;
import org.tron.p2p.discover.message.kad.PingMessage;
import org.tron.p2p.discover.message.kad.PongMessage;
import org.tron.p2p.discover.socket.UdpEvent;
import org.tron.p2p.discover.message.*;

@Slf4j(topic = "discover")
public class NodeHandler {

  private static long pingTimeout = 15_000;
  private Node sourceNode;
  private Node node;
  private State state;
  private KadService kadService;
  //  private NodeStatistics nodeStatistics;
  private NodeHandler replaceCandidate;
  private InetSocketAddress inetSocketAddress;
  private AtomicInteger pingTrials = new AtomicInteger(3);
  private volatile boolean waitForPong = false;
  private volatile boolean waitForNeighbors = false;
  private volatile long pingSent;
  @Getter
  @Setter
  private int reputation;


  public NodeHandler(Node node, KadService kadService) {
    this.node = node;
    this.kadService = kadService;
    this.inetSocketAddress = new InetSocketAddress(node.getHost(), node.getPort());
    //this.nodeStatistics = new NodeStatistics();
    changeState(State.DISCOVERED);
  }

  public InetSocketAddress getInetSocketAddress() {
    return inetSocketAddress;
  }

  public Node getSourceNode() {
    return sourceNode;
  }

  public void setSourceNode(Node sourceNode) {
    this.sourceNode = sourceNode;
  }

  public Node getNode() {
    return node;
  }

  public void setNode(Node node) {
    this.node = node;
  }

  public State getState() {
    return state;
  }

//  public NodeStatistics getNodeStatistics() {
//    return nodeStatistics;
//  }

  private void challengeWith(NodeHandler replaceCandidate) {
    this.replaceCandidate = replaceCandidate;
    changeState(State.EVICTCANDIDATE);
  }

  // Manages state transfers
  public void changeState(State newState) {
    State oldState = state;
    if (newState == State.DISCOVERED) {
      if (sourceNode != null && sourceNode.getPort() != node.getPort()) {
        changeState(State.DEAD);
      } else {
        sendPing();
      }
    }

    if (newState == State.ALIVE) {
      Node evictCandidate = kadService.getTable().addNode(this.node);
      if (evictCandidate == null) {
        newState = State.ACTIVE;
      } else {
        NodeHandler evictHandler = kadService.getNodeHandler(evictCandidate);
        if (evictHandler.state != State.EVICTCANDIDATE) {
          evictHandler.challengeWith(this);
        }
      }
    }
    if (newState == State.ACTIVE) {
      if (oldState == State.ALIVE) {
        // new node won the challenge
        kadService.getTable().addNode(node);
      } else if (oldState == State.EVICTCANDIDATE) {
        // nothing to do here the node is already in the table
      } else {
        // wrong state transition
      }
    }

    if (newState == State.DEAD) {
      if (oldState == State.EVICTCANDIDATE) {
        // lost the challenge
        // Removing ourselves from the table
        kadService.getTable().dropNode(node);
        // Congratulate the winner
        replaceCandidate.changeState(State.ACTIVE);
      } else if (oldState == State.ALIVE) {
        // ok the old node was better, nothing to do here
      } else {
        // wrong state transition
      }
    }

    if (newState == State.EVICTCANDIDATE) {
      // trying to survive, sending ping and waiting for pong
      sendPing();
    }
    state = newState;
  }

  public void handlePing(PingMessage msg) {
    if (!kadService.getTable().getNode().equals(node)) {
      sendPong();
    }
    node.setP2pVersion(msg.getVersion());
    if (!node.isConnectible(Parameter.p2pConfig.getVersion())) {
      changeState(State.DEAD);
    } else if (state.equals(State.DEAD)) {
      changeState(State.DISCOVERED);
    }
  }

  public void handlePong(PongMessage msg) {
    if (waitForPong) {
      waitForPong = false;
      //getNodeStatistics().discoverMessageLatency.add(System.currentTimeMillis() - pingSent);
      //Metrics.histogramObserve(MetricKeys.Histogram.PING_PONG_LATENCY,
      //    (System.currentTimeMillis() - pingSent) / Metrics.MILLISECONDS_PER_SECOND);
      //getNodeStatistics().lastPongReplyTime.set(System.currentTimeMillis());
      node.setId(msg.getFrom().getId());
      node.setP2pVersion(msg.getVersion());
      if (!node.isConnectible(Parameter.p2pConfig.getVersion())) {
        changeState(State.DEAD);
      } else {
        changeState(State.ALIVE);
      }
    }
  }

  public void handleNeighbours(NeighborsMessage msg) {
    if (!waitForNeighbors) {
      log.warn("Receive neighbors from {} without send find nodes", node.getHost());
      return;
    }
    waitForNeighbors = false;
    for (Node n : msg.getNodes()) {
      if (!kadService.getPublicHomeNode().getHexId().equals(n.getHexId())) {
        kadService.getNodeHandler(n);
      }
    }
  }

  public void handleFindNode(FindNodeMessage msg) {
    List<Node> closest = kadService.getTable().getClosestNodes(msg.getTargetId());
    sendNeighbours(closest, msg.getTimestamp());
  }

  public void handleTimedOut() {
    waitForPong = false;
    if (pingTrials.getAndDecrement() > 0) {
      sendPing();
    } else {
      if (state == State.DISCOVERED || state == State.EVICTCANDIDATE) {
        changeState(State.DEAD);
      } else {
        // TODO just influence to reputation
      }
    }
  }

  public void sendPing() {
    PingMessage msg = new PingMessage(kadService.getPublicHomeNode(), getNode());
    waitForPong = true;
    pingSent = System.currentTimeMillis();
    sendMessage(msg);

    if (kadService.getPongTimer().isShutdown()) {
      return;
    }
    kadService.getPongTimer().schedule(() -> {
      try {
        if (waitForPong) {
          waitForPong = false;
          handleTimedOut();
        }
      } catch (Exception e) {
        log.error("Unhandled exception in pong timer schedule", e);
      }
    }, pingTimeout, TimeUnit.MILLISECONDS);
  }

  public void sendPong() {
    Message pong = new PongMessage(kadService.getPublicHomeNode());
    sendMessage(pong);
  }

  public void sendFindNode(byte[] target) {
    waitForNeighbors = true;
    FindNodeMessage msg = new FindNodeMessage(kadService.getPublicHomeNode(), target);
    sendMessage(msg);
  }

  public void sendNeighbours(List<Node> neighbours, long sequence) {
    Message msg = new NeighborsMessage(kadService.getPublicHomeNode(), neighbours, sequence);
    sendMessage(msg);
  }

  private void sendMessage(Message msg) {
    kadService.sendOutbound(new UdpEvent(msg, getInetSocketAddress()));
    //nodeStatistics.messageStatistics.addUdpOutMessage(msg.getType());
  }

  @Override
  public String toString() {
    return "NodeHandler[state: " + state + ", node: " + node.getHost() + ":" + node.getPort() + "]";
  }

  public enum State {
    /**
     * The new node was just discovered either by receiving it with Neighbours message or by
     * receiving Ping from a new node In either case we are sending Ping and waiting for Pong If the
     * Pong is received the node becomes {@link #ALIVE} If the Pong was timed out the node becomes
     * {@link #DEAD}
     */
    DISCOVERED,
    /**
     * The node didn't send the Pong message back withing acceptable timeout This is the final
     * state
     */
    DEAD,
    /**
     * The node responded with Pong and is now the candidate for inclusion to the table If the table
     * has bucket space for this node it is added to table and becomes {@link #ACTIVE} If the table
     * bucket is full this node is challenging with the old node from the bucket if it wins then old
     * node is dropped, and this node is added and becomes {@link #ACTIVE} else this node becomes
     * {@link #DEAD}
     */
    ALIVE,
    /**
     * The node is included in the table. It may become {@link #EVICTCANDIDATE} if a new node wants
     * to become Active but the table bucket is full.
     */
    ACTIVE,
    /**
     * This node is in the table but is currently challenging with a new Node candidate to survive
     * in the table bucket If it wins then returns back to {@link #ACTIVE} state, else is evicted
     * from the table and becomes {@link #DEAD}
     */
    EVICTCANDIDATE
  }
}
