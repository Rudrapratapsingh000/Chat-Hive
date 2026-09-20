package com.rudra.chatserver.dao;

import com.rudra.chatserver.entity.Message;
import com.rudra.chatserver.util.HibernateUtil;
import org.hibernate.Session;
import org.hibernate.Transaction;

public class MessageDAO {

    /**
     * Save a system or legacy message without an explicit receiver.
     */
    public static void saveMessage(String sender, String content) {
        Transaction tx = null;
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            tx = session.beginTransaction();
            Message m = new Message(sender, content);
            session.persist(m);
            tx.commit();
        } catch (Exception e) {
            if (tx != null) tx.rollback();
            System.err.println("Message save error: " + e.getMessage());
        }
    }

    /**
     * Save a normal point‑to‑point chat message.
     */
    public static void saveMessage(String sender, String receiver, String content) {
        Transaction tx = null;
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            tx = session.beginTransaction();
            Message m = new Message(sender, receiver, content);
            session.persist(m);
            tx.commit();
        } catch (Exception e) {
            if (tx != null) tx.rollback();
            System.err.println("Message save error: " + e.getMessage());
        }
    }
}
