package fi.otavanopisto.chat.history;

import java.io.StringReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.commons.lang3.StringUtils;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.dom4j.QName;
import org.jivesoftware.database.DbConnectionManager;
import org.jivesoftware.openfire.IQHandlerInfo;
import org.jivesoftware.openfire.auth.UnauthorizedException;
import org.jivesoftware.openfire.handler.IQHandler;
import org.jivesoftware.util.XMPPDateTimeFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xmpp.packet.IQ;
import org.xmpp.packet.PacketError;

public class IQChatHistoryHandler extends IQHandler {

  private IQHandlerInfo info;
  private static final Logger logger = LoggerFactory.getLogger(IQChatHistoryHandler.class);
  public static final String NAMESPACE = "otavanopisto:chat:history";

  public IQChatHistoryHandler() {
    super("Chat History handler");
    info = new IQHandlerInfo("query", NAMESPACE);
  }

  @Override
  public IQHandlerInfo getInfo() {
    return info;
  }

  @Override
  public IQ handleIQ(IQ packet) throws UnauthorizedException {
    Element query = packet.getChildElement();
    String type = query.element("type").getText();
    if (query.element("includeStanzaIds") != null) {
      if (StringUtils.equals(type,  "groupchat")) {
        return handleIQWithStanzas(packet);
      }
      else {
        return buildErrorResponse(packet, PacketError.Condition.bad_request,
            String.format("includeStanzaIds is only valid with type groupchat, was %s", type));
      }
    }
    String with = query.element("with").getText();
    Element maxElement = query.element("max");
    long max = maxElement == null ? 0 : Long.valueOf(maxElement.getText());
    long before = 0;
    Element beforeElement = query.element("before");
    if (beforeElement != null) {
      ZonedDateTime beforeDateTime = ZonedDateTime.parse(beforeElement.getText());
      before = beforeDateTime.toInstant().toEpochMilli();
    }
    ArrayList<Object> sqlParams = new ArrayList<Object>();
    StringBuilder sql = new StringBuilder();
    sql.append("select fromJID,fromJIDResource,toJID,toJIDResource,sentDate,body from ofmessagearchive");
    if (StringUtils.equals("chat", type)) {
      sql.append(" where ((fromJid=? and toJID=?) or (fromJid=? and toJID=?))");
      sqlParams.add(packet.getFrom().asBareJID().toString());
      sqlParams.add(with);
      sqlParams.add(with);
      sqlParams.add(packet.getFrom().asBareJID().toString());
    }
    else if (StringUtils.equals("groupchat", type)) {
      
      // Since Monitoring Service plugin stores messages even after a MUC room has been
      // deleted, limit the MUC history to messages sent after the room was created
      
      String roomName = StringUtils.substringBefore(with, "@");
      String xmppDomain = getXmppDomain();
      if (StringUtils.isEmpty(xmppDomain)) {
        return buildEmptyResponse(packet);
      }
      String mucService = StringUtils.substringBefore(StringUtils.substringAfter(with, "@"), "." + xmppDomain);
      Long roomCreationDate = getRoomCreationDate(mucService, roomName);
      if (roomCreationDate == null) {
        return buildEmptyResponse(packet);
      }
      sql.append(" where toJID=? and sentDate>?");
      sqlParams.add(with);
      sqlParams.add(roomCreationDate);
    }
    else {
      return buildErrorResponse(packet, PacketError.Condition.bad_request,
          String.format("type needs to be chat|groupchat, was %s", type));
    }
    if (before > 0) {
      sql.append(" and sentDate<?");
      sqlParams.add(before);
    }
    sql.append(" order by sentDate desc");
    if (max > 0) {
      sql.append(" limit ?");
      sqlParams.add(max + 1);
    }
    Connection connection = null;
    PreparedStatement preparedStatement = null;
    ResultSet resultSet = null;
    try {
      connection = DbConnectionManager.getConnection();
      preparedStatement = connection.prepareStatement(sql.toString());
      for (int i = 0; i < sqlParams.size(); i++) {
        preparedStatement.setObject(i + 1, sqlParams.get(i));
      }
      resultSet = preparedStatement.executeQuery();
      int matches = 0;
      boolean hasMore = false;
      List<Element> messages = new ArrayList<Element>();
      while (resultSet.next()) {
        matches++;
        if (max > 0 && matches > max) {
          hasMore = true;
          break;
        }
        Element historyMessage = DocumentHelper.createElement(QName.get("historyMessage", NAMESPACE));
        historyMessage.addElement("fromJID").setText(getFullJid(resultSet.getString("fromJID"), resultSet.getString("fromJIDResource")));
        historyMessage.addElement("toJID").setText(getFullJid(resultSet.getString("toJID"), resultSet.getString("toJIDResource")));
        historyMessage.addElement("timestamp").setText(XMPPDateTimeFormat.format(new Date(resultSet.getLong("sentDate"))));
        historyMessage.addElement("message").setText(resultSet.getString("body"));
        messages.add(historyMessage);
      }

      IQ response = IQ.createResultIQ(packet);
      Element responseQuery = response.setChildElement("query", NAMESPACE);
      String queryId = packet.getChildElement().attributeValue("queryId");
      if (!StringUtils.isEmpty(queryId)) {
        responseQuery.addAttribute("queryId", queryId);
      }
      if (!hasMore) {
        responseQuery.addAttribute("complete", "true");
      }
      Collections.reverse(messages);
      for (Element message : messages) {
        responseQuery.add(message);
      }
      return response;
    }
    catch (SQLException sqle) {
      logger.error("Error fetching messages", sqle);
      return buildErrorResponse(packet, PacketError.Condition.internal_server_error, "Error fetching messages");
    }
    finally {
      DbConnectionManager.closeConnection(resultSet, preparedStatement, connection);
    }
  }
  
  private IQ handleIQWithStanzas(IQ packet) throws UnauthorizedException {
    Element query = packet.getChildElement();
    String with = query.element("with").getText();
    Element maxElement = query.element("max");
    long max = maxElement == null ? 0 : Long.valueOf(maxElement.getText());
    long before = 0;
    Element beforeElement = query.element("before");
    if (beforeElement != null) {
      ZonedDateTime beforeDateTime = ZonedDateTime.parse(beforeElement.getText());
      before = beforeDateTime.toInstant().toEpochMilli();
    }

    // Get room id and creation date
    
    String roomName = StringUtils.substringBefore(with, "@");
    String xmppDomain = getXmppDomain();
    if (StringUtils.isEmpty(xmppDomain)) {
      return buildEmptyResponse(packet);
    }
    String mucService = StringUtils.substringBefore(StringUtils.substringAfter(with, "@"), "." + xmppDomain);
    Long roomId = getRoomId(mucService, roomName);
    if (roomId == null) {
      return buildEmptyResponse(packet);
    }
    Long roomCreationDate = getRoomCreationDate(mucService, roomName);
    if (roomCreationDate == null) {
      return buildEmptyResponse(packet);
    }
    
    // Fetch messages

    ArrayList<Object> sqlParams = new ArrayList<Object>();
    StringBuilder sql = new StringBuilder();
    sql.append("select sender,nickname,logTime,body,stanza from ofmucconversationlog where roomID=? and cast(logTime as unsigned)>? and nickname is not null");
    sqlParams.add(roomId);
    sqlParams.add(roomCreationDate);
    if (before > 0) {
      sql.append(" and cast(logTime as unsigned)<?");
      sqlParams.add(before);
    }
    sql.append(" order by cast(logTime as unsigned) desc");
    if (max > 0) {
      sql.append(" limit ?");
      sqlParams.add(max + 1);
    }
    Connection connection = null;
    PreparedStatement preparedStatement = null;
    ResultSet resultSet = null;
    try {
      connection = DbConnectionManager.getConnection();
      preparedStatement = connection.prepareStatement(sql.toString());
      for (int i = 0; i < sqlParams.size(); i++) {
        preparedStatement.setObject(i + 1, sqlParams.get(i));
      }
      resultSet = preparedStatement.executeQuery();
      int matches = 0;
      boolean hasMore = false;
      List<Element> messages = new ArrayList<Element>();
      while (resultSet.next()) {
        matches++;
        if (max > 0 && matches > max) {
          hasMore = true;
          break;
        }
        
        String stanzaId = null;
        NamedNodeMap bodyAttributes = null;
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
          DocumentBuilder builder = factory.newDocumentBuilder();
          Document doc = builder.parse(new InputSource(new StringReader(resultSet.getString("stanza"))));
          NodeList nodeList = doc.getDocumentElement().getElementsByTagName("stanza-id");
          if (nodeList.getLength() == 1) {
            stanzaId = nodeList.item(0).getAttributes().getNamedItem("id").getNodeValue();
          }
          nodeList = doc.getDocumentElement().getElementsByTagName("body");
          if (nodeList.getLength() == 1) {
            bodyAttributes = nodeList.item(0).getAttributes();
          }
        }
        catch (Exception e) {
          return buildErrorResponse(packet, PacketError.Condition.internal_server_error,
              String.format("Unable to parse message stanza: %s", e.getMessage()));
        }        
        
        Element historyMessage = DocumentHelper.createElement(QName.get("historyMessage", NAMESPACE));
        if (stanzaId != null) {
          historyMessage.addElement("stanzaId").setText(stanzaId);
        }
        historyMessage.addElement("fromJID").setText(resultSet.getString("sender"));
        historyMessage.addElement("toJID").setText(getFullJid(with, resultSet.getString("nickname")));
        historyMessage.addElement("timestamp").setText(XMPPDateTimeFormat.format(new Date(resultSet.getLong("logTime"))));
        Element messageElement = historyMessage.addElement("message");
        messageElement.setText(resultSet.getString("body"));
        if (bodyAttributes != null) {
          for (int i = 0; i < bodyAttributes.getLength(); i++) {
            Node bodyAttribute = bodyAttributes.item(i);
            messageElement.addAttribute(bodyAttribute.getNodeName(), bodyAttribute.getNodeValue());
          }
        }
        messages.add(historyMessage);
      }

      IQ response = IQ.createResultIQ(packet);
      Element responseQuery = response.setChildElement("query", NAMESPACE);
      String queryId = packet.getChildElement().attributeValue("queryId");
      if (!StringUtils.isEmpty(queryId)) {
        responseQuery.addAttribute("queryId", queryId);
      }
      if (!hasMore) {
        responseQuery.addAttribute("complete", "true");
      }
      Collections.reverse(messages);
      for (Element message : messages) {
        responseQuery.add(message);
      }
      return response;
    }
    catch (SQLException sqle) {
      logger.error("Error fetching messages", sqle);
      return buildErrorResponse(packet, PacketError.Condition.internal_server_error, "Error fetching messages");
    }
    finally {
      DbConnectionManager.closeConnection(resultSet, preparedStatement, connection);
    }
  }
  
  private Long getRoomId(String mucService, String roomName) {
    Long roomId = null;
    Connection connection = null;
    PreparedStatement preparedStatement = null;
    ResultSet resultSet = null;
    try {
      connection = DbConnectionManager.getConnection();
      preparedStatement = connection.prepareStatement("select r.roomID from ofmucroom r, ofmucservice s where r.serviceId=s.serviceId and s.subdomain=? and r.name=?");
      preparedStatement.setString(1, mucService);
      preparedStatement.setString(2, roomName);
      resultSet = preparedStatement.executeQuery();
      if (resultSet.next()) {
        roomId = Long.valueOf(resultSet.getString("roomID"));
      }
    }
    catch (SQLException sqle) {
      logger.error("Error fetching room id", sqle);
    }
    finally {
      DbConnectionManager.closeConnection(resultSet, preparedStatement, connection);
    }
    return roomId;
  }
  
  private Long getRoomCreationDate(String mucService, String roomName) {
    Long creationDate = null;
    Connection connection = null;
    PreparedStatement preparedStatement = null;
    ResultSet resultSet = null;
    try {
      connection = DbConnectionManager.getConnection();
      preparedStatement = connection.prepareStatement("select r.creationDate from ofmucroom r, ofmucservice s where r.serviceId=s.serviceId and s.subdomain=? and r.name=?");
      preparedStatement.setString(1, mucService);
      preparedStatement.setString(2, roomName);
      resultSet = preparedStatement.executeQuery();
      if (resultSet.next()) {
        creationDate = Long.valueOf(resultSet.getString("creationDate"));
      }
    }
    catch (SQLException sqle) {
      logger.error("Error fetching room creation date", sqle);
    }
    finally {
      DbConnectionManager.closeConnection(resultSet, preparedStatement, connection);
    }
    return creationDate;
  }
  
  private String getXmppDomain() {
    String domain = null;
    Connection connection = null;
    PreparedStatement preparedStatement = null;
    ResultSet resultSet = null;
    try {
      connection = DbConnectionManager.getConnection();
      preparedStatement = connection.prepareStatement("select propValue from ofproperty where name=?");
      preparedStatement.setString(1, "xmpp.domain");
      resultSet = preparedStatement.executeQuery();
      if (resultSet.next()) {
        domain = resultSet.getString("propValue");
      }
    }
    catch (SQLException sqle) {
      logger.error("Error fetching XMPP domain", sqle);
    }
    finally {
      DbConnectionManager.closeConnection(resultSet, preparedStatement, connection);
    }
    return domain;
  }

  private IQ buildEmptyResponse(IQ packet) {
    IQ response = IQ.createResultIQ(packet);
    Element responseQuery = response.setChildElement("query", NAMESPACE);
    String queryId = packet.getChildElement().attributeValue("queryId");
    if (!StringUtils.isEmpty(queryId)) {
      responseQuery.addAttribute("queryId", queryId);
    }
    responseQuery.addAttribute("complete", "true");
    return response;
  }

  private IQ buildErrorResponse(IQ packet, PacketError.Condition condition, String message) {
    IQ reply = IQ.createResultIQ(packet);
    reply.setChildElement(packet.getChildElement().createCopy());
    final PacketError packetError = new PacketError(condition);
    if (message != null && !message.isEmpty()) {
      packetError.setText(message);
    }
    reply.setError(packetError);
    return reply;
  }
  
  private String getFullJid(String bareJid, String resource) {
    return StringUtils.isEmpty(resource) ? bareJid : String.format("%s/%s", bareJid, resource);
  }

}