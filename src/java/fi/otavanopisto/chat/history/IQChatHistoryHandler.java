package fi.otavanopisto.chat.history;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

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
    String with = query.element("with").getText();
    Element maxElement = query.element("max");
    long max = maxElement == null ? 0 : Long.valueOf(maxElement.getText());
    Element beforeIdElement = query.element("before-id");
    long beforeId = beforeIdElement == null ? 0 : Long.valueOf(beforeIdElement.getText());
    long before = 0;
    Element beforeElement = query.element("before");
    if (beforeElement != null) {
      ZonedDateTime beforeDateTime = ZonedDateTime.parse(beforeElement.getText());
      before = beforeDateTime.toInstant().toEpochMilli();
    }
    ArrayList<Object> sqlParams = new ArrayList<Object>();
    StringBuilder sql = new StringBuilder();
    sql.append("select messageID,fromJID,fromJIDResource,toJID,toJIDResource,sentDate,body from ofmessagearchive");
    if (StringUtils.equals("chat", type)) {
      sql.append(" where ((fromJid=? and toJID=?) or (fromJid=? and toJID=?))");
      sqlParams.add(packet.getFrom().asBareJID().toString());
      sqlParams.add(with);
      sqlParams.add(with);
      sqlParams.add(packet.getFrom().asBareJID().toString());
    }
    else if (StringUtils.equals("groupchat", type)) {
      sql.append(" where toJID=?");
      sqlParams.add(with);
    }
    else {
      return buildErrorResponse(packet, PacketError.Condition.bad_request,
          String.format("type attribute needs to be chat|groupchat, was %s", type));
    }
    if (before > 0) {
      sql.append(" and sentDate<?");
      sqlParams.add(before);
    }
    else if (beforeId > 0) {
      sql.append(" and messageID<?");
      sqlParams.add(beforeId);
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
        historyMessage.addElement("id").setText(resultSet.getString("messageID"));
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