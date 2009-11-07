/*
   Copyright 2009 NEERC team

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
package ru.ifmo.neerc.chat.xmpp;

import org.jivesoftware.smack.ConnectionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.ifmo.neerc.chat.client.AbstractChatClient;
import ru.ifmo.neerc.chat.client.Chat;
import ru.ifmo.neerc.chat.client.ChatMessage;
import ru.ifmo.neerc.chat.message.Message;
import ru.ifmo.neerc.chat.message.ServerMessage;
import ru.ifmo.neerc.chat.message.UserMessage;
import ru.ifmo.neerc.chat.user.UserEntry;
import ru.ifmo.neerc.chat.user.UserRegistry;
import ru.ifmo.neerc.task.Task;
import ru.ifmo.neerc.task.TaskActions;
import ru.ifmo.neerc.task.TaskRegistry;
import ru.ifmo.neerc.task.TaskRegistryListener;
import ru.ifmo.neerc.task.TaskStatus;

import javax.swing.*;
import java.awt.*;
import java.util.*;

/**
 * @author Evgeny Mandrikov
 */
public class XmppChatClient extends AbstractChatClient {
    private static final Logger LOG = LoggerFactory.getLogger(XmppChatClient.class);

    private XmppChat xmppChat;
    private HashSet<String> newTaskIds = new HashSet<String>();

    public XmppChatClient() {
        final String name = System.getProperty("username");

        UserRegistry userRegistry = UserRegistry.getInstance();
        user = userRegistry.findOrRegister(name);
        userRegistry.putOnline(name);
        userRegistry.setRole(name, "moderator");


        chat = new MyChat();
        setupUI();

        MyListener listener = new MyListener();
        xmppChat = new XmppChat(name, listener);

        xmppChat.getConnection().addConnectionListener(listener);
        if (xmppChat.getMultiUserChat().isJoined()) {
            final String message = "Connected";
            setConnectionStatus(message);
        } else {
            setConnectionError("Unable to connect");
        }

        taskRegistry.addListener(new MyListener());
        alertNewTasks();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                new XmppChatClient().setVisible(true);
            }
        });
    }

    private void alertNewTasks() {
        StringBuilder description = new StringBuilder("New tasks:\n");
        boolean hasNew = false;
        for (Task task: TaskRegistry.getInstance().getTasks()) {
            TaskStatus status = task.getStatus(user.getName());
            if (status == null || !TaskActions.STATUS_NEW.equals(status.getType())) {
                continue;
            }
            if (newTaskIds.contains(task.getId())) continue;
            newTaskIds.add(task.getId());
            hasNew = true;
            LOG.debug("got new task " + task.getTitle());
            description.append(task.getTitle()).append("\n");
            ChatMessage chatMessage = ChatMessage.createTaskMessage(
                "!!! New task '" + task.getTitle() + "' has been assigned to you !!!",
                (new Date())
            );
            addToModel(chatMessage);
        }
        if (!hasNew) return;
        
        final String str = description.toString();
        new Thread(new Runnable() {
            public void run() {
                JOptionPane.showMessageDialog(
                    XmppChatClient.this,
                    str,
                    "New tasks",
                    JOptionPane.WARNING_MESSAGE
                );
                // TODO: send ack?
            }
        }).start();
        if (isBeepOn) {
            System.out.print('\u0007'); // PC-speaker beep
        }
    }

    private void setConnectionStatus(String status, boolean isError) {
        if (status.equals(connectionStatus.getText())) {
            return;
        }
        if (!isError) {
            LOG.info("Connection status: " + status);
            connectionStatus.setForeground(Color.BLUE);
        } else {
            LOG.error("Connection status: " + status);
            connectionStatus.setForeground(Color.RED);
        }
        connectionStatus.setText(status);
    }

    private void setConnectionStatus(String status) {
        setConnectionStatus(status, false);
    }

    private void setConnectionError(String error) {
        setConnectionStatus(error, true);
    }

    private String getNick(String participant) {
        return UserRegistry.getInstance().findOrRegister(participant).getName();
    }

    private class MyChat implements Chat {
        @Override
        public void write(Message message) {
            xmppChat.write(message);
        }
        @Override
        public void write(Task task) {
            xmppChat.write(task);
        }
        @Override
        public void write(Task task, TaskStatus status) {
            xmppChat.write(task, status);
        }
    }

    private class MyListener implements MUCListener, ConnectionListener, TaskRegistryListener {
        @Override
        public void connectionClosed() {
            final String message = "Connection closed";
            setConnectionError(message);
            processMessage(new ServerMessage(message));
        }

        @Override
        public void connectionClosedOnError(Exception e) {
            final String message = "Connection closed on error";
            setConnectionError(message);
            processMessage(new ServerMessage(message));
            for (UserEntry user : UserRegistry.getInstance().getUsers()) {
                UserRegistry.getInstance().putOffline(user.getName());
            }
        }

        @Override
        public void reconnectingIn(int i) {
            setConnectionError("Reconnecting in " + i);
        }

        @Override
        public void reconnectionSuccessful() {
            final String message = "Reconnected";
            setConnectionStatus(message);
            processMessage(new ServerMessage(message));
        }

        @Override
        public void reconnectionFailed(Exception e) {
            setConnectionError("Reconnection failed");
        }

        @Override
        public void joined(String participant) {
            UserRegistry.getInstance().putOnline(participant);
            processMessage(new ServerMessage(
                    "User " + getNick(participant) + " online"
            ));
        }

        @Override
        public void left(String participant) {
            UserRegistry.getInstance().putOffline(participant);
            processMessage(new ServerMessage(
                    "User " + getNick(participant) + " offline"
            ));
        }

        @Override
        public void roleChanged(String jid, String role) {
            UserRegistry.getInstance().setRole(jid, role);
            String nick = getNick(jid);
//            processMessage(new ServerMessage(
//                    "User " + nick + " now " + role
//            ));
            if (nick.equals(user.getName())) {
                taskPanel.toolBar.setVisible("moderator".equals(role));
            }
        }

        @Override
        public void messageReceived(String jid, String message, Date timestamp) {
            processMessage(new UserMessage(jid, message, timestamp));
        }

        @Override
        public void historyMessageReceived(String jid, String message, Date timestamp) {
//            addToModel(ChatMessage.createUserMessage(new UserMessage(jid, message, timestamp)));
            processMessage(new UserMessage(jid, message, timestamp));
        }

        @Override
        public void taskChanged(Task task) {
            TaskStatus status = task.getStatus(user.getName());
            if (status == null || !TaskActions.STATUS_NEW.equals(status.getType())) {
                return;
            }
            alertNewTasks();
        }

        @Override
        public void tasksReset() {}
    }
}