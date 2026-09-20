package com.rudra.chatserver.dao;

import com.rudra.chatserver.entity.User;
import com.rudra.chatserver.util.HibernateUtil;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.hibernate.query.Query;

public class UserDAO {

    public static boolean register(String username, String password) {
        Transaction tx = null;
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            tx = session.beginTransaction();
            User user = new User(username, password);
            session.persist(user);
            tx.commit();
            return true;
        } catch (Exception e) {
            if (tx != null) tx.rollback();
            System.err.println("Register error: " + e.getMessage());
            return false;
        }
    }

    public static boolean login(String username, String password) {
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            Query<User> q = session.createQuery(
                "FROM User WHERE username = :u AND password = :p", User.class);
            q.setParameter("u", username);
            q.setParameter("p", password);
            User user = q.uniqueResult();
            return user != null;
        } catch (Exception e) {
            System.err.println("Login error: " + e.getMessage());
            return false;
        }
    }
}
