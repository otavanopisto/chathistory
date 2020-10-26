package fi.otavanopisto.chat.history;

import java.io.File;

import org.jivesoftware.openfire.IQRouter;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.container.Plugin;
import org.jivesoftware.openfire.container.PluginManager;
import org.jivesoftware.openfire.handler.IQHandler;

public class ChatHistoryPlugin implements Plugin {
  
  private IQRouter iqRouter;
  private IQHandler chatHistoryHandler;

  public ChatHistoryPlugin() {
  }

  public void initializePlugin(PluginManager pluginManager, File file) {
    chatHistoryHandler = new IQChatHistoryHandler();
    iqRouter = XMPPServer.getInstance().getIQRouter();
    iqRouter.addHandler(chatHistoryHandler);
  }

  public void destroyPlugin() {
    if (iqRouter != null) {
      iqRouter.removeHandler(chatHistoryHandler);
    }
  }

}
