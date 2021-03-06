/*
 * Copyright (c) 2013 Faculty of Computer Science, University of Vienna
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package univie.cs.pps;

import java.util.Arrays;
import java.util.Collection;

import rice.p2p.commonapi.Application;
import rice.p2p.commonapi.CancellableTask;
import rice.p2p.commonapi.Endpoint;
import rice.p2p.commonapi.Id;
import rice.p2p.commonapi.Message;
import rice.p2p.commonapi.Node;
import rice.p2p.commonapi.NodeHandle;
import rice.p2p.commonapi.RouteMessage;
import rice.p2p.scribe.Scribe;
import rice.p2p.scribe.ScribeContent;
import rice.p2p.scribe.ScribeImpl;
import rice.p2p.scribe.ScribeMultiClient;
import rice.p2p.scribe.Topic;
import rice.pastry.commonapi.PastryIdFactory;
import rice.pastry.standard.RandomNodeIdFactory;
import univie.cs.pps.utils.ValueReader;

/**
 * An implementation of the Push-Sum protocol as a Pastry application.
 * <p>
 * This class implements the {@link Application} interface to send messages to
 * random nodes in the ring and the {@link ScribeMultiClient} interface to
 * broadcast notifications to all nodes.
 * 
 * @author Dario Seidl
 * 
 */
public class PastryPushSum implements Application, ScribeMultiClient
{
	/**
	 * The instance identifier of the application.
	 */
	public static final String INSTANCE = "push-sum-instance";

	/**
	 * The instance identifier of the Scribe instance.
	 */
	public static final String SCRIBE_INSTANCE = "push-sum-scribe-instance";

	/**
	 * The name of the Scribe topic used for reset notifications.
	 */
	public static final String SCRIBE_TOPIC = "push-sum-scribe-topic";

	private final Node node;
	private final Endpoint endpoint;

	private final long stepSize;
	private final boolean trace;
	private final int updateInterval;
	private final ValueReader valueReader;
	private final double min;
	private final double max;

	private final Scribe scribe;
	private final Topic resetTopic;

	private int step = 0;
	private boolean active;
	private boolean transition;

	private double trueValue;
	private double value;
	private double weight;
	private double valueBuffer;
	private double weightBuffer;

	private CancellableTask timer;

	/**
	 * Constructs and registers a new {@link PastryPushSum} application.
	 * 
	 * @param node
	 *            the node at which this application will be registered.
	 * @param stepSize
	 *            the time between sending messages to the node to signal the
	 *            start of the next step.
	 * @param updateInterval
	 *            the number of steps between updating node value. If set to 0
	 *            the node values will never be updated.
	 * @param valueReader
	 *            the {@link ValueReader} instance from which the node obtains
	 *            its true value.
	 * @param min
	 *            the domain-specific minimum possible value, used as a lower
	 *            bound for the estimates.
	 * @param max
	 *            the domain-specific maximum possible value, used as an upper
	 *            bound for the estimates.
	 * @param trace
	 *            if set to {@code true}, the node will print a notice about all
	 *            sent and received messages to the standard output.
	 */
	public PastryPushSum(Node node, int stepSize, int updateInterval, ValueReader valueReader, double min, double max, boolean trace)
	{
		this.node = node;
		this.stepSize = stepSize;
		this.updateInterval = updateInterval;
		this.valueReader = valueReader;
		this.min = min;
		this.max = max;
		this.trace = trace;

		// obtain true value from the value reader
		trueValue = valueReader.getCurrentValue();

		value = trueValue;
		valueBuffer = trueValue;
		weight = 1.;
		weightBuffer = 1.;

		// register application
		endpoint = node.buildEndpoint(this, INSTANCE);
		endpoint.register();

		// schedule timer messages
		timer = endpoint.scheduleMessage(new TimerMessage(), 0, stepSize);
		active = true;

		// subscribe to a Scribe topic for reset notifications
		scribe = new ScribeImpl(node, SCRIBE_INSTANCE);
		resetTopic = new Topic(new PastryIdFactory(node.getEnvironment()), SCRIBE_TOPIC);
		scribe.subscribe(resetTopic, this, null, null);
	}

	/**
	 * Stops the participation of this node.
	 * <p>
	 * There is no way to actually remove an application from the ring. We
	 * simply stop sending messages. When receiving a message while stopped,
	 * this node will forward the message to another random node.
	 */
	public void stop()
	{
		if (active)
		{
			log("stop.");

			timer.cancel();
			active = false;
		}
	}

	/**
	 * Resumes participation of this node.
	 */
	public void resume()
	{
		if (!active)
		{
			log("resume.");

			timer = endpoint.scheduleMessage(new TimerMessage(), 0, stepSize);
			active = true;
		}
	}

	/**
	 * Returns whether this node is active.
	 */
	public boolean isActive()
	{
		return active;
	}

	/**
	 * Returns the true value of this node. The true value is updated
	 * periodically with the value returned by the {@link ValueReader} specified
	 * in the constructor.
	 */
	public double getTrueValue()
	{
		return trueValue;
	}

	/**
	 * Returns the current value, used by the Push-Sum protocol to estimate the
	 * mean.
	 */
	public double getValue()
	{
		return value;
	}

	/**
	 * Returns the current weight, used by the Push-Sum protocol to estimate the
	 * mean.
	 */
	public double getWeight()
	{
		return weight;
	}

	/**
	 * Returns the average value of all nodes in the ring, as estimated by the
	 * Push-Sum protocol. The estimate is obtained by {@code value / weight},
	 * bounded by the {@code min} and {@code max} values given in the
	 * constructor.
	 * <p>
	 * If the true value has been updated recently, this will return the true
	 * value instead, until one message from another node is received, as in
	 * this case it serves as a better estimate.
	 */
	public double getEstimate()
	{
		return transition ? trueValue : Math.max(min, Math.min(value / weight, max));
	}

	/**
	 * Sends a reset notification to all nodes.
	 * <p>
	 * Since this is not a barrier, the resets introduce an error in the
	 * accuracy of the estimates. Overall it may be better not to do any resets,
	 * letting the contribution of stopped nodes "fade out".
	 */
	public void broadcastReset()
	{
		scribe.publish(resetTopic, new ResetNotification());
	}

	// == Application methods ============ //

	/**
	 * Called when this node receives a message.
	 * <p>
	 * When receiving a timer message, sum up the values and weights from all
	 * messages received since the last step and send half of the new value and
	 * weight to itself and to a random neighbor.
	 * <p>
	 * When receiving a message from another node, store the received value and
	 * weight in a buffer for the next step.
	 */
	@Override
	public void deliver(Id id, Message message)
	{
		if (trace)
		{
			log("received " + message);
		}

		// next step
		if (message instanceof TimerMessage)
		{
			step++;

			// sum up received values
			value = valueBuffer;
			weight = weightBuffer;

			// update value
			if (updateInterval > 0 && step % updateInterval == 0)
			{
				double newValue = valueReader.getCurrentValue();
				value += newValue - trueValue;
				transition = newValue != trueValue;
				trueValue = newValue;
			}

			// send to self
			valueBuffer = value / 2;
			weightBuffer = weight / 2;

			// send to random neighbor
			Id randomId = (new RandomNodeIdFactory(node.getEnvironment())).generateNodeId();
			ValueWeightMessage response = new ValueWeightMessage(endpoint.getId(), randomId, value / 2, weight / 2);
			endpoint.route(randomId, response, null);

			if (trace)
			{
				log("sending " + response);
			}

		}

		// message from another node
		else if (message instanceof ValueWeightMessage)
		{
			if (active)
			{
				ValueWeightMessage vw = (ValueWeightMessage) message;

				valueBuffer += vw.getValue();
				weightBuffer += vw.getWeight();

				transition = false;
			}
			else
			{
				// if we stopped participating, but are still in the ring,
				// forward messages to another random node
				Id randomId = (new RandomNodeIdFactory(node.getEnvironment())).generateNodeId();
				endpoint.route(randomId, message, null);
			}
		}
	}

	/**
	 * Called when this node is about to forward a message.
	 * <p>
	 * All messages are forwarded in this application.
	 */
	@Override
	public boolean forward(RouteMessage message)
	{
		if (trace)
		{
			log("forward " + message);
		}

		return true;
	}

	/**
	 * Called when a node joins or leaves the neighbor set of this node.
	 * <p>
	 * No action is taken when a node joins or leaves the neighbor set of this
	 * node.
	 */
	@Override
	public void update(NodeHandle handle, boolean joined)
	{
		if (trace)
		{
			log("update " + handle + ((joined) ? " joined" : " left"));
		}
	}

	// == ScribeMultiClient methods ============ //

	/**
	 * Called when a message is received for a topic this node has subscribed.
	 * <p>
	 * This application only uses one topic to broadcast reset notifications.
	 * When receiving a reset notification, reset the current value to the
	 * original value and set the weight to 1.
	 */
	@Override
	public void deliver(Topic topic, ScribeContent content)
	{
		if (trace)
		{
			log("deliver (" + topic + "," + content + ")");
		}

		if (content instanceof ResetNotification)
		{
			this.value = trueValue;
			this.valueBuffer = trueValue;
			this.weight = 1.;
			this.weightBuffer = 1.;
		}
	}

	/**
	 * Called when an anycast is received for a topic this node has subscribed.
	 * <p>
	 * This class never uses anycasts.
	 */
	@Override
	public boolean anycast(Topic topic, ScribeContent content)
	{
		return false;
	}

	/**
	 * Called when an child is added to a topic this node has subscribed.
	 * <p>
	 * No action, except logging, is taken in this event.
	 */
	@Override
	public void childAdded(Topic topic, NodeHandle child)
	{
		if (trace)
		{
			log("child added (" + topic + "," + child + ")");
		}
	}

	/**
	 * Called when an child is removed to a topic this node has subscribed.
	 * <p>
	 * No action, except logging, is taken in this event.
	 */
	@Override
	public void childRemoved(Topic topic, NodeHandle child)
	{
		if (trace)
		{
			log("child removed (" + topic + "," + child + ")");
		}
	}

	/**
	 * Called when subscribing to a topic failed.
	 * <p>
	 * No action, except logging, is taken in this event.
	 */
	@Override
	@Deprecated
	public void subscribeFailed(Topic topic)
	{
		if (trace)
		{
			log("subscribe failed (" + topic + ")");
		}
	}

	/**
	 * Called when subscribing to a topic failed.
	 * <p>
	 * No action, except logging, is taken in this event.
	 */
	@Override
	public void subscribeFailed(Collection<Topic> topics)
	{
		if (trace)
		{
			log("subscribe failed (" + Arrays.toString(topics.toArray()) + ")");
		}
	}

	/**
	 * Called when subscribing to a topic succeeded.
	 * <p>
	 * No action, except logging, is taken in this event.
	 */
	@Override
	public void subscribeSuccess(Collection<Topic> topics)
	{
		if (trace)
		{
			log("subscribe success (" + Arrays.toString(topics.toArray()) + ")");
		}
	}

	// ========================================== //

	@Override
	public String toString()
	{
		return this.getClass().getSimpleName() + endpoint.getId();
	}

	private void log(String text)
	{
		System.out.format("# [%d] %s: %s%n", node.getEnvironment().getTimeSource().currentTimeMillis(), this, text);
	}
}

class TimerMessage implements Message
{
	@Override
	public int getPriority()
	{
		return Message.DEFAULT_PRIORITY;
	}

	@Override
	public String toString()
	{
		return this.getClass().getSimpleName();
	}
}

class ResetNotification implements ScribeContent
{
	@Override
	public String toString()
	{
		return this.getClass().getSimpleName();
	}
}
