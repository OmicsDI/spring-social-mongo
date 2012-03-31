/*
 * Copyright 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.social.connect.mongo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.Map.Entry;

import org.springframework.social.connect.Connection;
import org.springframework.social.connect.ConnectionKey;
import org.springframework.util.MultiValueMap;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Order;
import org.springframework.data.mongodb.core.query.Query;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import static org.springframework.data.mongodb.core.query.Query.query;
import static org.springframework.data.mongodb.core.query.Criteria.*;

/**
 * A service for the spring connections management using Mongodb.
 *
 * @author Carlo P. Micieli
 */
@Service
public class MongoConnectionService implements ConnectionService {

	private MongoTemplate mongoTemplate;
	private ConnectionConverter converter;
	
	@Autowired
	public MongoConnectionService(MongoTemplate mongoTemplate, ConnectionConverter converter) {
		this.mongoTemplate = mongoTemplate;
		this.converter = converter;
	}
		
	/* (non-Javadoc)
	 * @see org.springframework.social.connect.mongo.ConnectionService#getMaxRank(java.lang.String, java.lang.String)
	 */
	@Override
	public int getMaxRank(String userId, String providerId) { 
		// select coalesce(max(rank) + 1, 1) as rank from UserConnection where userId = ? and providerId = ?
		Query q = query(where("userId").is(userId).and("providerId").is(providerId));
		q.sort().on("rank", Order.DESCENDING);
		MongoConnection cnn = mongoTemplate.findOne(q, MongoConnection.class);
		
		if (cnn==null)
			return 1;
		
		return cnn.getRank() + 1;
	}
	
	/* (non-Javadoc)
	 * @see org.springframework.social.connect.mongo.ConnectionService#create(java.lang.String, org.springframework.social.connect.Connection, int)
	 */
	@Override
	public void create(String userId, Connection<?> userConn, int rank) {
		MongoConnection mongoCnn = converter.convert(userConn);
		mongoCnn.setUserId(userId);
		mongoCnn.setRank(rank);
		mongoTemplate.insert(mongoCnn);
	}
	
	/* (non-Javadoc)
	 * @see org.springframework.social.connect.mongo.ConnectionService#update(java.lang.String, org.springframework.social.connect.Connection)
	 */
	@Override
	public void update(String userId, Connection<?> userConn) {
		MongoConnection mongoCnn = converter.convert(userConn);
		mongoCnn.setUserId(userId);
		mongoTemplate.save(mongoCnn);
	}
	
	/* (non-Javadoc)
	 * @see org.springframework.social.connect.mongo.ConnectionService#remove(java.lang.String, org.springframework.social.connect.ConnectionKey)
	 */
	@Override
	public void remove(String userId, ConnectionKey connectionKey) {
		//delete where userId = ? and providerId = ? and providerUserId = ?
		mongoTemplate.remove(query(where("userId").is(userId)
				.and("providerId").is(connectionKey.getProviderId())
				.and("providerUserId").is(connectionKey.getProviderUserId())));		
	}
	
	/* (non-Javadoc)
	 * @see org.springframework.social.connect.mongo.ConnectionService#remove(java.lang.String, java.lang.String)
	 */
	@Override
	public void remove(String userId, String providerId) {
		// delete where userId = ? and providerId = ?
		mongoTemplate.remove(query(where("userId").is(userId)
				.and("providerId").is(providerId)));
	}
	
	/* (non-Javadoc)
	 * @see org.springframework.social.connect.mongo.ConnectionService#getPrimaryConnection(java.lang.String, java.lang.String)
	 */
	@Override
	public Connection<?> getPrimaryConnection(String userId, String providerId) {
		// where userId = ? and providerId = ? and rank = 1
		Query q = query(where("userId").is(userId).
				and("providerId").is(providerId).
				and("rank").is(1));
		
		MongoConnection mc = mongoTemplate.findOne(q, MongoConnection.class);
		return converter.convert(mc);
	}
	
	/* (non-Javadoc)
	 * @see org.springframework.social.connect.mongo.ConnectionService#getConnection(java.lang.String, java.lang.String, java.lang.String)
	 */
	@Override
	public Connection<?> getConnection(String userId, String providerId, String providerUserId) {
		// where userId = ? and providerId = ? and providerUserId = ?
		Query q = query(where("userId").is(userId)
				.and("providerId").is(providerId)
				.and("providerUserId").is(providerUserId));
					
		MongoConnection mc = mongoTemplate.findOne(q, MongoConnection.class);
		return converter.convert(mc);
	}
	
	/* (non-Javadoc)
	 * @see org.springframework.social.connect.mongo.ConnectionService#getConnections(java.lang.String)
	 */
	@Override
	public List<Connection<?>> getConnections(String userId) {
		// select where userId = ? order by providerId, rank
		Query q = query(where("userId").is(userId));
		q.sort().on("providerId", Order.ASCENDING).on("rank", Order.ASCENDING);
		
		return runQuery(q);
	}
	
	/* (non-Javadoc)
	 * @see org.springframework.social.connect.mongo.ConnectionService#getConnections(java.lang.String, java.lang.String)
	 */
	@Override
	public List<Connection<?>> getConnections(String userId, String providerId) {
		// where userId = ? and providerId = ? order by rank
		Query q = new Query(where("userId").is(userId).and("providerId").is(providerId));
		q.sort().on("rank", Order.ASCENDING);
		
		return runQuery(q);
	}
	
	/* (non-Javadoc)
	 * @see org.springframework.social.connect.mongo.ConnectionService#getConnections(java.lang.String, org.springframework.util.MultiValueMap)
	 */
	@Override
	public List<Connection<?>> getConnections(String userId, MultiValueMap<String, String> providerUsers) {
		// userId? and providerId = ? and providerUserId in (?, ?, ...) order by providerId, rank
		
		if (providerUsers == null || providerUsers.isEmpty()) {
			throw new IllegalArgumentException("Unable to execute find: no providerUsers provided");
		}
		
		List<Criteria> lc = new ArrayList<>();
		for (Iterator<Entry<String, List<String>>> it = providerUsers.entrySet().iterator(); it.hasNext();) {
			Entry<String, List<String>> entry = it.next();
			String providerId = entry.getKey();
			
			lc.add(where("providerId").is(providerId)
				.and("providerUserId").in(entry.getValue()));
		}
		
		Criteria criteria = where("userId").is(userId);
		criteria.orOperator(lc.toArray(new Criteria[lc.size()]));
		
		Query q = new Query(criteria);
		q.sort().on("providerId", Order.ASCENDING).on("rank", Order.ASCENDING);
		
		return runQuery(q);
	}

	/* (non-Javadoc)
	 * @see org.springframework.social.connect.mongo.ConnectionService#getUserIds(java.lang.String, java.util.Set)
	 */
	@Override
	public Set<String> getUserIds(String providerId, Set<String> providerUserIds) {
		//select userId from " + tablePrefix + "UserConnection where providerId = :providerId and providerUserId in (:providerUserIds)
		Query q = query(where("providerId").is(providerId)
				.and("providerUserId").in(new ArrayList<String>(providerUserIds)));
		q.fields().include("userId");
		
		List<MongoConnection> results = mongoTemplate.find(q, MongoConnection.class);
		Set<String> userIds = new HashSet<>();
		for (MongoConnection mc : results) {
			userIds.add(mc.getUserId());
		}
		
		return userIds;
	}
	
	/* (non-Javadoc)
	 * @see org.springframework.social.connect.mongo.ConnectionService#getUserIds(java.lang.String, java.lang.String)
	 */
	@Override
	public List<String> getUserIds(String providerId, String providerUserId) {
		 //select userId where providerId = ? and providerUserId = ?", 		
		Query q = query(where("providerId").is(providerId)
				.and("providerUserId").is(providerUserId));
		q.fields().include("userId");
		
		List<MongoConnection> results = mongoTemplate.find(q, MongoConnection.class);
		List<String> userIds = new ArrayList<>();
		for (MongoConnection mc : results) {
			userIds.add(mc.getUserId());
		}
		
		return userIds;
	}
	
	private List<Connection<?>> runQuery(Query query) {
		List<MongoConnection> results = mongoTemplate.find(query, MongoConnection.class);
		List<Connection<?>> l = new ArrayList<>();
		for (MongoConnection mc : results) {
			l.add(converter.convert(mc));
		}
		
		return l;
	}
}