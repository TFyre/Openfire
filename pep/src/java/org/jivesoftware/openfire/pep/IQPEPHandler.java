/**
 * $RCSfile: $
 * $Revision: $
 * $Date: $
 *
 * Copyright (C) 2007 Jive Software. All rights reserved.
 *
 * This software is published under the terms of the GNU Public License (GPL),
 * a copy of which is included in this distribution.
 */

package org.jivesoftware.openfire.pep;

import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.dom4j.QName;
import org.jivesoftware.database.DbConnectionManager;
import org.jivesoftware.openfire.IQHandlerInfo;
import org.jivesoftware.openfire.PresenceManager;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.auth.UnauthorizedException;
import org.jivesoftware.openfire.disco.ServerFeaturesProvider;
import org.jivesoftware.openfire.disco.ServerIdentitiesProvider;
import org.jivesoftware.openfire.disco.UserIdentitiesProvider;
import org.jivesoftware.openfire.disco.UserItemsProvider;
import org.jivesoftware.openfire.handler.IQHandler;
import org.jivesoftware.openfire.interceptor.InterceptorManager;
import org.jivesoftware.openfire.interceptor.PacketInterceptor;
import org.jivesoftware.openfire.interceptor.PacketRejectedException;
import org.jivesoftware.openfire.pubsub.CollectionNode;
import org.jivesoftware.openfire.pubsub.LeafNode;
import org.jivesoftware.openfire.pubsub.Node;
import org.jivesoftware.openfire.pubsub.NodeSubscription;
import org.jivesoftware.openfire.pubsub.PubSubEngine;
import org.jivesoftware.openfire.pubsub.models.AccessModel;
import org.jivesoftware.openfire.roster.Roster;
import org.jivesoftware.openfire.roster.RosterItem;
import org.jivesoftware.openfire.session.ClientSession;
import org.jivesoftware.openfire.session.Session;
import org.jivesoftware.openfire.user.PresenceEventDispatcher;
import org.jivesoftware.openfire.user.PresenceEventListener;
import org.jivesoftware.openfire.user.UserManager;
import org.jivesoftware.openfire.user.UserNotFoundException;
import org.jivesoftware.util.Log;
import org.xmpp.forms.DataForm;
import org.xmpp.forms.FormField;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;
import org.xmpp.packet.Packet;
import org.xmpp.packet.PacketError;
import org.xmpp.packet.Presence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <p>
 * An IQHandler used to implement XEP-0163: "Personal Eventing via Pubsub."
 * </p>
 * 
 * <p>
 * For each user on the server there is an associated PEPService interacting
 * with a single PubSubEngine for managing the user's PEP nodes.
 * </p>
 * 
 * <p>
 * An IQHandler can only handle one namespace in its IQHandlerInfo. However, PEP
 * related packets are seen having a variety of different namespaces. Thus,
 * classes like IQPEPOwnerHandler are used to forward packets having these other
 * namespaces to IQPEPHandler.handleIQ().
 * <p>
 * 
 * <p>
 * This handler is used for the following namespaces:
 * <ul>
 * <li><i>http://jabber.org/protocol/pubsub</i></li>
 * <li><i>http://jabber.org/protocol/pubsub#owner</i></li>
 * </ul>
 * </p>
 * 
 * @author Armando Jagucki
 * 
 */
public class IQPEPHandler extends IQHandler implements ServerIdentitiesProvider, ServerFeaturesProvider, UserIdentitiesProvider, UserItemsProvider,
        PresenceEventListener, PacketInterceptor {

    // Map of PEP services. Table, Key: bare JID (String); Value: PEPService
    private Map<String, PEPService> pepServices;

    /**
     * Nodes to send filtered notifications for, table: key JID (String); value Set of nodes
     * 
     * filteredNodesMap are used for Contact Notification Filtering as described in XEP-0163. The JID
     * of a user is associated with a set of PEP node IDs they are interested in receiving notifications
     * for.
     */
    private Map<String, HashSet<String>> filteredNodesMap = new ConcurrentHashMap<String, HashSet<String>>();

    private IQHandlerInfo info;

    private PubSubEngine pubSubEngine = null;

    /**
     * A map of all known full JIDs that have sent presences from a remote server.
     * table: key Bare JID (String); value HashSet of JIDs
     * 
     * This map is convenient for sending notifications to the full JID of remote users
     * that have sent available presences to the PEP service. 
     */
    private Map<String, HashSet<JID>> knownRemotePresences = new ConcurrentHashMap<String, HashSet<JID>>();

    private static final String GET_PEP_SERVICES = "SELECT DISTINCT serviceID FROM pubsubNode";

    public IQPEPHandler() {
        super("Personal Eventing Handler");
        pepServices = new ConcurrentHashMap<String, PEPService>();
        info = new IQHandlerInfo("pubsub", "http://jabber.org/protocol/pubsub");
    }

    @Override
    public void initialize(XMPPServer server) {
        super.initialize(server);

        // Listen to presence events to manage PEP auto-subscriptions.
        PresenceEventDispatcher.addListener(this);

        pubSubEngine = new PubSubEngine(server.getPacketRouter());

        // Restore previous PEP services for which nodes exist in the database.
        Connection con = null;
        PreparedStatement pstmt = null;
        try {
            con = DbConnectionManager.getConnection();
            // Get all PEP services
            pstmt = con.prepareStatement(GET_PEP_SERVICES);
            ResultSet rs = pstmt.executeQuery();
            // Restore old PEPServices
            while (rs.next()) {
                String serviceID = rs.getString(1);

                // Create a new PEPService if serviceID looks like a bare JID.
                if (serviceID.indexOf("@") >= 0) {
                    PEPService pepService = new PEPService(server, serviceID);
                    pepServices.put(serviceID, pepService);
                    if (Log.isDebugEnabled()) {
                        Log.debug("PEP: Restored service for " + serviceID + " from the database.");
                    }
                }
            }
            rs.close();
            pstmt.close();
        }
        catch (SQLException sqle) {
            Log.error(sqle);
        }
        finally {
            try {
                if (pstmt != null)
                    pstmt.close();
            }
            catch (Exception e) {
                Log.error(e);
            }
            try {
                if (con != null)
                    con.close();
            }
            catch (Exception e) {
                Log.error(e);
            }
        }

        // Add this PEP handler as a packet interceptor so we may deal with
        // client packets that send disco#info's explaining capabilities
        // including PEP contact notification filters.
        InterceptorManager.getInstance().addInterceptor(this);
    }

    public void start() {
        super.start();
        for (PEPService service : pepServices.values()) {
            pubSubEngine.start(service);
        }
    }

    public void stop() {
        super.stop();
        for (PEPService service : pepServices.values()) {
            pubSubEngine.shutdown(service);
        }
    }

    @Override
    public IQHandlerInfo getInfo() {
        return info;
    }

    @Override
    public IQ handleIQ(IQ packet) throws UnauthorizedException {
        // TODO: Finish implementing ... ;)

        if (packet.getTo() == null && packet.getType() == IQ.Type.set) {
            String jidFrom = packet.getFrom().toBareJID();

            PEPService pepService = pepServices.get(jidFrom);

            // If no service exists yet for jidFrom, create one.
            if (pepService == null) {
                // Return an error if the packet is from an anonymous or otherwise
                // unregistered user.
                if (!UserManager.getInstance().isRegisteredUser(packet.getFrom())) {
                    IQ reply = IQ.createResultIQ(packet);
                    reply.setChildElement(packet.getChildElement().createCopy());
                    reply.setError(PacketError.Condition.not_allowed);
                    return reply;
                }

                pepService = new PEPService(XMPPServer.getInstance(), jidFrom);
                pepServices.put(jidFrom, pepService);

                // Probe presences
                pubSubEngine.start(pepService);
                if (Log.isDebugEnabled()) {
                    Log.debug("PEP: " + jidFrom + " had a PEPService created");
                }

                // Those who already have presence subscriptions to jidFrom
                // will now automatically be subscribed to this new PEPService.
                try {
                    Roster roster = XMPPServer.getInstance().getRosterManager().getRoster(packet.getFrom().getNode());
                    for (RosterItem item : roster.getRosterItems()) {
                        if (item.getSubStatus() == RosterItem.SUB_BOTH || item.getSubStatus() == RosterItem.SUB_FROM) {
                            createSubscriptionToPEPService(pepService, item.getJid(), packet.getFrom());
                        }
                    }
                }
                catch (UserNotFoundException e) {
                    // Do nothing
                }

            }

            // If publishing a node, and the node doesn't exist, create it.
            Element childElement = packet.getChildElement();
            Element publishElement = childElement.element("publish");
            if (publishElement != null) {
                String nodeID = publishElement.attributeValue("node");
                
                // Do not allow User Avatar nodes to be created.
                // TODO: Implement XEP-0084
                if (nodeID.startsWith("http://www.xmpp.org/extensions/xep-0084.html")) {
                    IQ reply = IQ.createResultIQ(packet);
                    reply.setChildElement(packet.getChildElement().createCopy());
                    reply.setError(PacketError.Condition.feature_not_implemented);
                    return reply;
                }

                if (pepService.getNode(nodeID) == null) {
                    // Create the node
                    JID creator = new JID(jidFrom);
                    LeafNode newNode = new LeafNode(pepService, pepService.getRootCollectionNode(), nodeID, creator);
                    newNode.addOwner(creator);
                    newNode.saveToDB();
                    if (Log.isDebugEnabled()) {
                        Log.debug("PEP: Created node ('" + newNode.getNodeID() + "') for " + jidFrom);
                    }
                }
            }

            // Process with PubSub as usual.
            pubSubEngine.process(pepService, packet);

        }
        else if (packet.getType() == IQ.Type.get || packet.getType() == IQ.Type.set) {
            String jidTo = packet.getTo().toBareJID();

            PEPService pepService = pepServices.get(jidTo);

            if (pepService != null) {
                pubSubEngine.process(pepService, packet);
            }
            else {
                // Process with PubSub using a dummyService.
                PEPService dummyService = new PEPService(XMPPServer.getInstance(), packet.getFrom().toBareJID());
                pubSubEngine.process(dummyService, packet);
            }

        }
        else {
            // Ignore IQ packets of type 'error' or 'result'.
            return null;
        }

        // Other error flows are handled in pubSubEngine.process(...)
        return null;
    }

    /**
     * Generates and processes an IQ stanza that subscribes to a PEP service.
     * 
     * @param pepService the PEP service of the owner.
     * @param subscriber the JID of the entity that is subscribing to the PEP service.
     * @param owner      the JID of the owner of the PEP service.
     */
    private void createSubscriptionToPEPService(PEPService pepService, JID subscriber, JID owner) {
        // If `owner` has a PEP service, generate and process a pubsub subscription packet
        // that is equivalent to: (where 'from' field is JID of subscriber and 'to' field is JID of owner)
        //
        //        <iq type='set'
        //            from='nurse@capulet.com/chamber'
        //            to='juliet@capulet.com
        //            id='collsub'>
        //          <pubsub xmlns='http://jabber.org/protocol/pubsub'>
        //            <subscribe jid='nurse@capulet.com'/>
        //            <options>
        //              <x xmlns='jabber:x:data'>
        //                <field var='FORM_TYPE' type='hidden'>
        //                  <value>http://jabber.org/protocol/pubsub#subscribe_options</value>
        //                </field>
        //                <field var='pubsub#subscription_type'>
        //                  <value>items</value>
        //                </field>
        //                <field var='pubsub#subscription_depth'>
        //                  <value>all</value>
        //                </field>
        //              </x>
        //           </options>
        //         </pubsub>
        //        </iq>

        IQ subscriptionPacket = new IQ(IQ.Type.set);
        subscriptionPacket.setFrom(subscriber);
        subscriptionPacket.setTo(owner.toBareJID());

        Element pubsubElement = subscriptionPacket.setChildElement("pubsub", "http://jabber.org/protocol/pubsub");

        Element subscribeElement = pubsubElement.addElement("subscribe");
        subscribeElement.addAttribute("jid", subscriber.toBareJID());

        Element optionsElement = pubsubElement.addElement("options");
        Element xElement = optionsElement.addElement(QName.get("x", "jabber:x:data"));

        DataForm dataForm = new DataForm(xElement);

        FormField formField = dataForm.addField();
        formField.setVariable("FORM_TYPE");
        formField.setType(FormField.Type.hidden);
        formField.addValue("http://jabber.org/protocol/pubsub#subscribe_options");

        formField = dataForm.addField();
        formField.setVariable("pubsub#subscription_type");
        formField.addValue("items");

        formField = dataForm.addField();
        formField.setVariable("pubsub#subscription_depth");
        formField.addValue("all");

        pubSubEngine.process(pepService, subscriptionPacket);
    }

    /**
     * Implements ServerIdentitiesProvider and UserIdentitiesProvider, adding
     * the PEP identity to the respective disco#info results.
     */
    public Iterator<Element> getIdentities() {
        ArrayList<Element> identities = new ArrayList<Element>();
        Element identity = DocumentHelper.createElement("identity");
        identity.addAttribute("category", "pubsub");
        identity.addAttribute("type", "pep");
        identities.add(identity);
        return identities.iterator();
    }

    /**
     * Implements ServerFeaturesProvider to include all supported XEP-0060 features
     * in the server's disco#info result (as per section 4 of XEP-0163).
     */
    public Iterator<String> getFeatures() {
        return XMPPServer.getInstance().getPubSubModule().getFeatures(null, null, null);
    }

    /**
     * Implements UserItemsProvider, adding PEP related items to a disco#items
     * result.
     */
    public Iterator<Element> getUserItems(String name, JID senderJID) {
        ArrayList<Element> items = new ArrayList<Element>();

        String recipientJID = XMPPServer.getInstance().createJID(name, null).toBareJID();
        PEPService pepService = pepServices.get(recipientJID);

        if (pepService != null) {
            CollectionNode rootNode = pepService.getRootCollectionNode();

            Element defaultItem = DocumentHelper.createElement("item");
            defaultItem.addAttribute("jid", recipientJID);

            for (Node node : pepService.getNodes()) {
                // Do not include the root node as an item element.
                if (node == rootNode) {
                    continue;
                }

                AccessModel accessModel = node.getAccessModel();
                if (accessModel.canAccessItems(node, senderJID, new JID(recipientJID))) {
                    Element item = defaultItem.createCopy();
                    item.addAttribute("node", node.getNodeID());
                    items.add(item);
                }
            }
        }

        return items.iterator();
    }

    public void subscribedToPresence(JID subscriberJID, JID authorizerJID) {
        PEPService pepService = pepServices.get(authorizerJID.toBareJID());
        if (pepService != null) {
            createSubscriptionToPEPService(pepService, subscriberJID, authorizerJID);

            // Delete any leaf node subscriptions the subscriber may have already
            // had (since a subscription to the PEP service, and thus its leaf PEP
            // nodes, would be duplicating publish notifications from previous leaf
            // node subscriptions).
            CollectionNode rootNode = pepService.getRootCollectionNode();
            for (Node node : pepService.getNodes()) {
                if (rootNode.isChildNode(node)) {
                    for (NodeSubscription subscription : node.getSubscriptions(subscriberJID)) {
                        node.cancelSubscription(subscription);
                    }
                }
            }
            
            pepService.sendLastPublishedItem(subscriberJID);
        }
    }
    
    public void unsubscribedToPresence(JID unsubscriberJID, JID recipientJID) {
        if (Log.isDebugEnabled()) {
            Log.debug("PEP: " + unsubscriberJID + " unsubscribed from " + recipientJID + "'s presence.");
        }

        // Retrieve recipientJID's PEP service, if it exists.
        PEPService pepService = pepServices.get(recipientJID.toBareJID());        
        if (pepService == null) {
            return;
        }
        
        // Cancel unsubscriberJID's subscription to recipientJID's PEP service, if it exists.
        CollectionNode rootNode = pepService.getRootCollectionNode();
        NodeSubscription nodeSubscription = rootNode.getSubscription(unsubscriberJID);
        if (nodeSubscription != null) {
            rootNode.cancelSubscription(nodeSubscription);
            
            if (Log.isDebugEnabled()) {
                Log.debug("PEP: " + unsubscriberJID + " subscription to " + recipientJID + "'s PEP service was cancelled.");
            }
        }
    }

    public void unavailableSession(ClientSession session, Presence presence) {
        // Do nothing

    }

    public void availableSession(ClientSession session, Presence presence) {
        // On newly-available presences, send the last published item if the resource is a subscriber.

        JID newlyAvailableJID = presence.getFrom();

        PresenceManager presenceManager = XMPPServer.getInstance().getPresenceManager();

        for (PEPService pepService : pepServices.values()) {
            try {
                if (presenceManager.canProbePresence(newlyAvailableJID, pepService.getAddress().getNode())) {
                    pepService.sendLastPublishedItem(newlyAvailableJID);
                }
            }
            catch (UserNotFoundException e) {
                // Do nothing
            }
        }

    }

    public void presenceChanged(ClientSession session, Presence presence) {
        // Do nothing

    }

    public void presencePriorityChanged(ClientSession session, Presence presence) {
        // Do nothing

    }

    public void interceptPacket(Packet packet, Session session, boolean incoming, boolean processed) throws PacketRejectedException {
        if (processed && packet instanceof IQ && ((IQ) packet).getType() == IQ.Type.result) {
            // Examine the packet and return if it does not look like a disco#info result containing
            // Entity Capabilities for a client. The sooner we return the better, as this method will be called
            // quite a lot.
            Element element = packet.getElement();
            if (element == null) {
                return;
            }
            Element query = element.element("query");
            if (query == null) {
                return;
            }
            else {
                if (query.attributeValue("node") == null) {
                    return;
                }
                String queryNamespace = query.getNamespaceURI();
                if (queryNamespace == null || !queryNamespace.equals("http://jabber.org/protocol/disco#info")) {
                    return;
                }
            }

            if (Log.isDebugEnabled()) {
                Log.debug("PEP: Intercepted a caps result packet: " + packet.toString());
            }

            Iterator featuresIterator = query.elementIterator("feature");
            if (featuresIterator == null) {
                return;
            }

            // Get the sender's full JID considering they may be logged in from multiple
            // clients with different notification filters.
            String jidFrom = packet.getFrom().toString();

            // For each feature variable, or in this case node ID, ending in "+notify" -- add
            // the node ID to the set of filtered nodes that jidFrom is interested in being
            // notified about.
            //
            // If none of the feature variables contain the node ID ending in "+notify",
            // remove it from the set of filtered nodes that jidFrom is interested in being
            // notified about.
            HashSet<String> supportedNodesSet = new HashSet<String>();
            while (featuresIterator.hasNext()) {
                Element featureElement = (Element) featuresIterator.next();

                String featureVar = featureElement.attributeValue("var");
                if (featureVar == null) {
                    continue;
                }

                supportedNodesSet.add(featureVar);
            }

            for (String nodeID : supportedNodesSet) {
                if (nodeID.endsWith("+notify")) {
                    // Add the nodeID to the sender's filteredNodesSet.
                    HashSet<String> filteredNodesSet = filteredNodesMap.get(jidFrom);

                    if (filteredNodesSet == null) {
                        filteredNodesSet = new HashSet<String>();
                        filteredNodesSet.add(nodeID);
                        filteredNodesMap.put(jidFrom, filteredNodesSet);

                        if (Log.isDebugEnabled()) {
                            Log.debug("PEP: Created filteredNodesSet for " + jidFrom);
                            Log.debug("PEP: Added " + nodeID + " to " + jidFrom + "'s set of filtered nodes.");
                        }
                    }
                    else {
                        if (filteredNodesSet.add(nodeID)) {
                            if (Log.isDebugEnabled()) {
                                Log.debug("PEP: Added " + nodeID + " to " + jidFrom + "'s set of filtered nodes: ");
                                Iterator tempIter = filteredNodesSet.iterator();
                                while (tempIter.hasNext()) {
                                    Log.debug("PEP: " + tempIter.next());
                                }
                            }
                        }
                    }

                }
                else {
                    // Remove the nodeID from the sender's filteredNodesSet if nodeIDPlusNotify
                    // is not in supportedNodesSet.
                    HashSet<String> filteredNodesSet = filteredNodesMap.get(jidFrom);
                    if (filteredNodesSet == null) {
                        return;
                    }

                    String nodeIDPlusNotify = nodeID + "+notify";

                    if (!supportedNodesSet.contains(nodeIDPlusNotify) && filteredNodesSet.remove(nodeIDPlusNotify)) {
                        if (Log.isDebugEnabled()) {
                            Log.debug("PEP: Removed " + nodeIDPlusNotify + " from " + jidFrom + "'s set of filtered nodes: ");
                            Iterator tempIter = filteredNodesSet.iterator();
                            while (tempIter.hasNext()) {
                                Log.debug("PEP: " + tempIter.next());
                            }
                        }
                    }
                }
            }
        }
        else if (incoming && processed && packet instanceof Presence) {
            // Cache newly-available presence resources for remote users (since the PresenceEventDispatcher
            // methods are not called for remote presence events).
            JID jidFrom  = packet.getFrom();
            JID jidTo = packet.getTo(); 

            if (!XMPPServer.getInstance().isLocal(jidFrom) && jidFrom != null && jidTo != null) {
                if (Log.isDebugEnabled()) {
                    Log.debug("PEP: received presence from: " + jidFrom + " to: " + jidTo);
                }

                HashSet<JID> remotePresenceSet = knownRemotePresences.get(jidTo.toBareJID());
                Presence.Type type = ((Presence) packet).getType();

                if (type != null && type == Presence.Type.unavailable) {
                    if (remotePresenceSet != null && remotePresenceSet.remove(jidFrom)) {
                        if (Log.isDebugEnabled()) {
                            Log.debug("PEP: removed " + jidFrom + " from " + jidTo + "'s knownRemotePresences");
                        }
                    }
                }
                else if (jidFrom.getResource() != null) {
                    if (remotePresenceSet != null) {
                        if (remotePresenceSet.add(jidFrom)) {
                            if (Log.isDebugEnabled()) {
                                Log.debug("PEP: added " + jidFrom + " to " + jidTo + "'s knownRemotePresences");
                            }
                        }
                    }
                    else {
                        remotePresenceSet = new HashSet<JID>();
                        if (remotePresenceSet.add(jidFrom)) {
                            if (Log.isDebugEnabled()) {
                                Log.debug("PEP: added " + jidFrom + " to " + jidTo + "'s knownRemotePresences");
                            }
                            knownRemotePresences.put(jidTo.toBareJID(), remotePresenceSet);
                        }
                    }

                    // Send last published item for newly-available remote resource.
                    availableSession((ClientSession) session, (Presence) packet);
                }
            }
        }
    }

    /**
     * Returns the filteredNodesMap.
     * 
     * @return the filteredNodesMap
     */
    public Map<String, HashSet<String>> getFilteredNodesMap() {
        return filteredNodesMap;
    }

    /**
     * Returns the knownRemotePresences map.
     * 
     * @return the knownRemotePresences map
     */
    public Map<String, HashSet<JID>> getKnownRemotePresenes() {
        return knownRemotePresences;
    }
}
