## Overview

A crude plugin to fetch archived messages from Openfire, whether public or private.

## Requirements

Requires Openfire Monitoring Service plugin (https://github.com/igniterealtime/openfire-monitoring-plugin). Tested with Openfire 4.6.0 and Monitoring Service 2.1.0. Probably also works with slightly earlier versions of either.

## Install

Run `mvn install` in project folder and you should get `chathistory-openfire-plugin-assembly.jar` in `target` folder. Add that as a plugin to your Openfire installation and you're good to go.

## Usage

Send

```
<iq type="set">
  <query xmlns="otavanopisto:chat:history" queryId="if-you-prefer"> <!-- queryId is optional -->
    <type>chat|groupchat</type> <!-- former for private messages, latter for MUC -->
    <with>bare JID of user (chat) or room (groupchat)</with>
    <max>the maximum number of messages to return</max> <!-- optional; get all if omitted (probably not a good idea) -->
    <includeStanzaIds/> <!-- optional; return message stanza ids (groupchat only) --> 
  </query>
</iq>
```

Get, in chronological order, the latest messages matching the query

```
<iq type="result">
  <query xmlns="otavanopisto:chat:history" queryId="if-you-prefer"> <!-- queryId if you sent one -->
    <historyMessage>
      <stanzaId>message stanza id</stanzaId> <!-- only if requested, only for groupchat -->
      <fromJID>JID of sender</fromJID>
      <toJID>JID of receiver</toJID>
      <timestamp>when the message was sent</timestamp>
      <message>message content</message>
    </historyMessage>
    ... // repeated for as many as were requested or available
  </query>
</iq>
```

If there's less than you requested, you get

```
<iq type="result">
  <query xmlns="otavanopisto:chat:history" queryId="if-you-prefer" complete="true"> <!-- if complete, you've reached the end so congrats! -->
    ...
  </query>
</iq>
```

Otherwise use `timestamp` of the first `historyMessage` in the previous batch to query further into the past

```
<iq type="set">
  <query xmlns="otavanopisto:chat:history" queryId="if-you-prefer">
    <type>chat|groupchat</type> <!-- former for private messages, latter for MUC -->
    <with>bare JID of user (chat) or room (groupchat)</with>
    <max>the maximum number of messages to return</max>
    <before>timestamp of the first message of the previous batch</before>
    <includeStanzaIds/> <!-- optional; return message stanza ids (groupchat only) --> 
  </query>
</iq>
```
