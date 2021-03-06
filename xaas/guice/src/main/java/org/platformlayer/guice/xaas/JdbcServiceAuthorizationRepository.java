package org.platformlayer.guice.xaas;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import javax.crypto.SecretKey;
import javax.inject.Inject;
import javax.inject.Provider;

import org.openstack.utils.Utf8;
import org.platformlayer.RepositoryException;
import org.platformlayer.crypto.AesUtils;
import org.platformlayer.ids.ProjectId;
import org.platformlayer.ids.ServiceMetadataKey;
import org.platformlayer.ids.ServiceType;
import org.platformlayer.jdbc.DbHelperBase;
import org.platformlayer.jdbc.JdbcTransaction;
import org.platformlayer.jdbc.JdbcUtils;
import org.platformlayer.ops.crypto.SecretHelper;
import org.platformlayer.xaas.model.ServiceAuthorization;
import org.platformlayer.xaas.repository.ServiceAuthorizationRepository;

import com.google.common.collect.Lists;

public class JdbcServiceAuthorizationRepository implements ServiceAuthorizationRepository {
    @Inject
    Provider<Connection> connectionProvider;

    @Override
    @JdbcTransaction
    public ServiceAuthorization findServiceAuthorization(ServiceType serviceType, ProjectId project) throws RepositoryException {
        try {
            Connection connection = connectionProvider.get();
            int serviceId = JdbcRepositoryHelpers.getServiceKey(connection, serviceType);
            int projectId = JdbcRepositoryHelpers.getProjectKey(connection, project);

            String sql = "SELECT data FROM service_authorizations WHERE service=? and project=?";

            List<ServiceAuthorization> items = Lists.newArrayList();

            PreparedStatement ps = connection.prepareStatement(sql);
            ResultSet rs = null;
            try {
                ps.setInt(1, serviceId);
                ps.setInt(2, projectId);
                rs = ps.executeQuery();
                while (rs.next()) {
                    items.add(mapRow(serviceType, rs));
                }
            } finally {
                JdbcUtils.safeClose(rs);
                JdbcUtils.safeClose(ps);
            }

            if (items.size() == 0)
                return null;
            if (items.size() != 1)
                throw new IllegalStateException("Found duplicate results for primary key: " + serviceType + ":" + project);
            return items.get(0);
        } catch (SQLException e) {
            throw new RepositoryException("Error running query", e);
        }
    }

    @Override
    @JdbcTransaction
    public ServiceAuthorization createAuthorization(ProjectId project, ServiceAuthorization authorization) throws RepositoryException {
        try {
            ServiceType serviceType = new ServiceType(authorization.serviceType);

            Connection connection = connectionProvider.get();
            int serviceId = JdbcRepositoryHelpers.getServiceKey(connection, serviceType);
            int projectId = JdbcRepositoryHelpers.getProjectKey(connection, project);

            final String sql = "INSERT INTO service_authorizations (service, project, data) VALUES (?, ?, ?)";

            PreparedStatement ps = connection.prepareStatement(sql);
            ResultSet rs = null;
            try {
                ps.setInt(1, serviceId);
                ps.setInt(2, projectId);
                ps.setString(3, authorization.data);

                int updateCount = ps.executeUpdate();
                if (updateCount != 1) {
                    throw new IllegalStateException("Unexpected number of rows inserted");
                }
            } finally {
                JdbcUtils.safeClose(rs);
                JdbcUtils.safeClose(ps);
            }

            return authorization;
        } catch (SQLException e) {
            throw new RepositoryException("Error running query", e);
        }
    }

    static ServiceAuthorization mapRow(ServiceType serviceType, ResultSet rs) throws SQLException {
        String data = rs.getString("data");

        ServiceAuthorization authorization = new ServiceAuthorization();
        authorization.data = data;
        authorization.serviceType = serviceType.getKey();

        return authorization;
    }

    @Override
    @JdbcTransaction
    public String findPrivateData(ServiceType serviceType, ProjectId project, ServiceMetadataKey metadataKey) throws RepositoryException {
        DbHelper db = new DbHelper(serviceType, project, metadataKey);

        List<String> values = Lists.newArrayList();

        ResultSet rs = null;
        try {
            rs = db.selectMetadata();
            while (rs.next()) {
                byte[] plaintext = secretHelper.decryptSecret(rs.getBytes("data"), rs.getBytes("secret"));
                String value = Utf8.toString(plaintext);
                values.add(value);
            }
        } catch (SQLException e) {
            throw new RepositoryException("Error running query", e);
        } finally {
            JdbcUtils.safeClose(rs);

            db.close();
        }

        if (values.size() == 0)
            return null;
        if (values.size() != 1)
            throw new IllegalStateException("Found duplicate results for primary key");
        return values.get(0);
    }

    @Inject
    SecretHelper secretHelper;

    @Override
    @JdbcTransaction
    public void setPrivateData(ServiceType serviceType, ProjectId project, ServiceMetadataKey metadataKey, String value) throws RepositoryException {
        DbHelper db = new DbHelper(serviceType, project, metadataKey);

        // TODO: Handle updates

        try {
            SecretKey secret = AesUtils.generateKey();

            byte[] plaintext = Utf8.getBytes(value);
            byte[] ciphertext = AesUtils.encrypt(secret, plaintext);

            // TODO: Encode this differently from items??
            byte[] secretData = secretHelper.encodeItemSecret(secret);

            db.insertMetadata(ciphertext, secretData);
        } catch (SQLException e) {
            throw new RepositoryException("Error running query", e);
        } finally {
            db.close();
        }
    }

    static interface Queries {
    }

    class DbHelper extends DbHelperBase {

        public DbHelper(ServiceType serviceType, ProjectId project, ServiceMetadataKey metadataKey) {
            super(connectionProvider.get());
            setAtom(serviceType);
            setAtom(project);
            setAtom(metadataKey);
        }

        public ResultSet selectMetadata() throws SQLException {
            String sql = "SELECT data, secret FROM service_metadata WHERE service=? and project=? and metadata_key=?";
            PreparedStatement ps = prepareStatement(sql);

            setAtom(ps, 1, ServiceType.class);
            setAtom(ps, 2, ProjectId.class);
            setAtom(ps, 3, ServiceMetadataKey.class);

            return ps.executeQuery();
        }

        public void insertMetadata(byte[] data, byte[] secret) throws SQLException {
            final String sql = "INSERT INTO service_metadata (service, project, metadata_key, data, secret) VALUES (?, ?, ?, ?, ?)";

            PreparedStatement ps = prepareStatement(sql);
            ResultSet rs = null;
            try {
                setAtom(ps, 1, ServiceType.class);
                setAtom(ps, 2, ProjectId.class);
                setAtom(ps, 3, ServiceMetadataKey.class);
                ps.setBytes(4, data);
                ps.setBytes(5, secret);

                int updateCount = ps.executeUpdate();
                if (updateCount != 1) {
                    throw new IllegalStateException("Unexpected number of rows inserted");
                }
            } finally {
                JdbcUtils.safeClose(rs);
            }
        }

    }

}
