/*
 * Copyright 2017-2018 the original author or authors.
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
package org.springframework.data.cassandra.core.mapping;

import static org.springframework.data.cassandra.core.cql.keyspace.CreateTableSpecification.createTable;
import static org.springframework.data.cassandra.core.mapping.CassandraSimpleTypeHolder.getDataTypeFor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.StreamSupport;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.data.cassandra.core.cql.CqlIdentifier;
import org.springframework.data.cassandra.core.cql.keyspace.CreateIndexSpecification;
import org.springframework.data.cassandra.core.cql.keyspace.CreateTableSpecification;
import org.springframework.data.cassandra.core.cql.keyspace.CreateUserTypeSpecification;
import org.springframework.data.cassandra.core.mapping.UserTypeUtil.FrozenLiteralDataType;
import org.springframework.data.convert.CustomConversions;
import org.springframework.data.convert.CustomConversions.StoreConversions;
import org.springframework.data.mapping.MappingException;
import org.springframework.data.mapping.context.AbstractMappingContext;
import org.springframework.data.mapping.context.MappingContext;
import org.springframework.data.mapping.model.Property;
import org.springframework.data.mapping.model.SimpleTypeHolder;
import org.springframework.data.util.Optionals;
import org.springframework.data.util.TypeInformation;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import com.datastax.driver.core.CodecRegistry;
import com.datastax.driver.core.DataType;
import com.datastax.driver.core.DataType.Name;
import com.datastax.driver.core.ProtocolVersion;
import com.datastax.driver.core.TupleType;

/**
 * Default implementation of a {@link MappingContext} for Cassandra using {@link CassandraPersistentEntity} and
 * {@link CassandraPersistentProperty} as primary abstractions.
 *
 * @author Alex Shvid
 * @author Matthew T. Adams
 * @author Mark Paluch
 * @author John Blum
 * @author Jens Schauder
 */
public class CassandraMappingContext
		extends AbstractMappingContext<BasicCassandraPersistentEntity<?>, CassandraPersistentProperty>
		implements ApplicationContextAware, BeanClassLoaderAware {

	private @Nullable ApplicationContext applicationContext;

	private @Nullable ClassLoader beanClassLoader;

	private CassandraPersistentEntityMetadataVerifier verifier =
			new CompositeCassandraPersistentEntityMetadataVerifier();

	private CustomConversions customConversions =
			new CustomConversions(StoreConversions.of(CassandraSimpleTypeHolder.HOLDER), Collections.emptyList());

	private Mapping mapping = new Mapping();

	private @Nullable UserTypeResolver userTypeResolver;

	// caches
	private final Map<CqlIdentifier, Set<CassandraPersistentEntity<?>>> entitySetsByTableName = new HashMap<>();

	private final Set<BasicCassandraPersistentEntity<?>> tableEntities = new HashSet<>();
	private final Set<BasicCassandraPersistentEntity<?>> userDefinedTypes = new HashSet<>();

	/**
	 * Create a new {@link CassandraMappingContext}.
	 */
	public CassandraMappingContext() {

		StoreConversions storeConversions = StoreConversions.of(CassandraSimpleTypeHolder.HOLDER);

		setCustomConversions(new CustomConversions(storeConversions, Collections.emptyList()));
		setSimpleTypeHolder(CassandraSimpleTypeHolder.HOLDER);
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.mapping.context.AbstractMappingContext#initialize()
	 */
	@Override
	public void initialize() {
		super.initialize();
		processMappingOverrides();
	}

	@SuppressWarnings("all")
	private void processMappingOverrides() {

		this.mapping.getEntityMappings().stream().filter(Objects::nonNull).forEach(entityMapping -> {

			Class<?> entityClass = getEntityClass(entityMapping.getEntityClassName());

			CassandraPersistentEntity<?> entity = getRequiredPersistentEntity(entityClass);

			String entityTableName = entityMapping.getTableName();

			if (StringUtils.hasText(entityTableName)) {
				entity.setTableName(CqlIdentifier.of(entityTableName, Boolean.valueOf(entityMapping.getForceQuote())));
			}

			processMappingOverrides(entity, entityMapping);

		});
	}

	private Class<?> getEntityClass(String entityClassName) {

		try {
			return ClassUtils.forName(entityClassName, this.beanClassLoader);
		}
		catch (ClassNotFoundException cause) {
			throw new IllegalStateException(
					String.format("Unknown persistent entity type name [%s]", entityClassName), cause);
		}
	}

	private static void processMappingOverrides(CassandraPersistentEntity<?> entity, EntityMapping entityMapping) {
		entityMapping.getPropertyMappings()
				.forEach((key, propertyMapping) -> processMappingOverride(entity, propertyMapping));
	}

	private static void processMappingOverride(CassandraPersistentEntity<?> entity, PropertyMapping mapping) {

		CassandraPersistentProperty property = entity.getRequiredPersistentProperty(mapping.getPropertyName());

		boolean forceQuote = Boolean.valueOf(mapping.getForceQuote());

		property.setForceQuote(forceQuote);

		if (StringUtils.hasText(mapping.getColumnName())) {
			property.setColumnName(CqlIdentifier.of(mapping.getColumnName(), forceQuote));
		}
	}

	/* (non-Javadoc)
	 * @see org.springframework.context.ApplicationContextAware#setApplicationContext(org.springframework.context.ApplicationContext)
	 */
	@Override
	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.applicationContext = applicationContext;
	}

	/* (non-Javadoc)
	 * @see org.springframework.beans.factory.BeanClassLoaderAware#setBeanClassLoader(java.lang.ClassLoader)
	 */
	public void setBeanClassLoader(ClassLoader beanClassLoader) {
		this.beanClassLoader = beanClassLoader;
	}

	/**
	 * Sets the {@link CustomConversions}.
	 *
	 * @param customConversions must not be {@literal null}.
	 * @since 1.5
	 */
	public void setCustomConversions(CustomConversions customConversions) {

		Assert.notNull(customConversions, "CustomConversions must not be null");

		this.customConversions = customConversions;
	}

	/**
	 * Sets the {@link Mapping}.
	 *
	 * @param mapping must not be {@literal null}.
	 */
	public void setMapping(Mapping mapping) {

		Assert.notNull(mapping, "Mapping must not be null");

		this.mapping = mapping;
	}

	/**
	 * Returns only {@link Table} entities.
	 *
	 * @since 1.5
	 */
	public Collection<BasicCassandraPersistentEntity<?>> getTableEntities() {
		return Collections.unmodifiableCollection(this.tableEntities);
	}

	/**
	 * Returns only those entities representing a user defined type.
	 *
	 * @since 1.5
	 */
	public Collection<CassandraPersistentEntity<?>> getUserDefinedTypeEntities() {
		return Collections.unmodifiableSet(this.userDefinedTypes);
	}

	/**
	 * Sets the {@link UserTypeResolver}.
	 *
	 * @param userTypeResolver must not be {@literal null}.
	 * @since 1.5
	 */
	public void setUserTypeResolver(UserTypeResolver userTypeResolver) {

		Assert.notNull(userTypeResolver, "UserTypeResolver must not be null");

		this.userTypeResolver = userTypeResolver;
	}

	/**
	 * @param verifier The verifier to set.
	 */
	public void setVerifier(CassandraPersistentEntityMetadataVerifier verifier) {
		this.verifier = verifier;
	}

	/**
	 * @return Returns the verifier.
	 */
	public CassandraPersistentEntityMetadataVerifier getVerifier() {
		return this.verifier;
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.mapping.context.AbstractMappingContext#addPersistentEntity(org.springframework.data.util.TypeInformation)
	 */
	@Override
	protected Optional<BasicCassandraPersistentEntity<?>> addPersistentEntity(TypeInformation<?> typeInformation) {

		// Prevent conversion types created as CassandraPersistentEntity
		Optional<BasicCassandraPersistentEntity<?>> optional = shouldCreatePersistentEntityFor(typeInformation)
				? super.addPersistentEntity(typeInformation)
				: Optional.empty();

		optional.ifPresent(entity -> {

			if (entity.isUserDefinedType()) {
				this.userDefinedTypes.add(entity);
			}
			// now do some caching of the entity

			Set<CassandraPersistentEntity<?>> entities = this.entitySetsByTableName
					.computeIfAbsent(entity.getTableName(), cqlIdentifier -> new HashSet<>());

			entities.add(entity);

			if (!entity.isUserDefinedType() && entity.isAnnotationPresent(Table.class)) {
				this.tableEntities.add(entity);
			}

		});

		return optional;
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.mapping.context.AbstractMappingContext#shouldCreatePersistentEntityFor(org.springframework.data.util.TypeInformation)
	 */
	@Override
	protected boolean shouldCreatePersistentEntityFor(TypeInformation<?> typeInfo) {
		return !this.customConversions.hasCustomWriteTarget(typeInfo.getType())
				&& super.shouldCreatePersistentEntityFor(typeInfo);
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.mapping.context.AbstractMappingContext#createPersistentEntity(org.springframework.data.util.TypeInformation)
	 */
	@Override
	protected <T> BasicCassandraPersistentEntity<T> createPersistentEntity(TypeInformation<T> typeInformation) {

		BasicCassandraPersistentEntity<T> entity = Optional.ofNullable(resolveUserDefinedType(typeInformation))
				.<BasicCassandraPersistentEntity<T>>map(resolvedUserDefinedType ->
					new CassandraUserTypePersistentEntity<>(typeInformation, getVerifier(), resolveUserTypeResolver()))
				.orElseGet(() ->
					new BasicCassandraPersistentEntity<>(typeInformation, getVerifier()));

		Optional.ofNullable(this.applicationContext).ifPresent(entity::setApplicationContext);

		return entity;
	}

	@Nullable
	private UserDefinedType resolveUserDefinedType(TypeInformation typeInformation) {
		return AnnotatedElementUtils.findMergedAnnotation(typeInformation.getType(), UserDefinedType.class);
	}

	@NonNull
	private UserTypeResolver resolveUserTypeResolver() {

		UserTypeResolver resolvedUserTypeResolver = this.userTypeResolver;

		Assert.state(resolvedUserTypeResolver != null, "UserTypeResolver must not be null");

		return resolvedUserTypeResolver;
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.mapping.context.AbstractMappingContext#createPersistentProperty(org.springframework.data.mapping.model.Property, org.springframework.data.mapping.model.MutablePersistentEntity, org.springframework.data.mapping.model.SimpleTypeHolder)
	 */
	@Override
	protected CassandraPersistentProperty createPersistentProperty(Property property,
			BasicCassandraPersistentEntity<?> owner, SimpleTypeHolder simpleTypeHolder) {

		BasicCassandraPersistentProperty persistentProperty =
				new BasicCassandraPersistentProperty(property, owner, simpleTypeHolder, this.userTypeResolver);

		Optional.ofNullable(this.applicationContext).ifPresent(persistentProperty::setApplicationContext);

		return persistentProperty;
	}

	/**
	 * Returns whether this mapping context has any entities mapped to the given table.
	 *
	 * @param name must not be {@literal null}.
	 * @return @return {@literal true} is this {@literal TableMetadata} is used by a mapping.
	 */
	public boolean usesTable(CqlIdentifier name) {

		Assert.notNull(name, "Table name must not be null");

		return this.entitySetsByTableName.containsKey(name);
	}

	/**
	 * Returns whether this mapping context has any entities using the given user type.
	 *
	 * @param name must not be {@literal null}.
	 * @return {@literal true} is this {@literal UserType} is used.
	 * @since 1.5
	 */
	public boolean usesUserType(CqlIdentifier name) {

		Assert.notNull(name, "User type name must not be null");

		return hasMappedUserType(name) || hasReferencedUserType(name);
	}

	private boolean hasMappedUserType(CqlIdentifier identifier) {
		return this.userDefinedTypes.stream().map(CassandraPersistentEntity::getTableName).anyMatch(identifier::equals);
	}

	private boolean hasReferencedUserType(CqlIdentifier identifier) {

		return getPersistentEntities().stream()
				.flatMap(entity -> StreamSupport.stream(entity.spliterator(), false))
				.flatMap(it -> Optionals.toStream(Optional.ofNullable(it.findAnnotation(CassandraType.class))))
				.map(CassandraType::userTypeName)
				.filter(StringUtils::hasText)
				.map(CqlIdentifier::of)
				.anyMatch(identifier::equals);
	}

	/**
	 * Returns a {@link CreateTableSpecification} for the given entity, including all mapping information.
	 *
	 * @param entity must not be {@literal null}.
	 */
	public CreateTableSpecification getCreateTableSpecificationFor(CassandraPersistentEntity<?> entity) {

		Assert.notNull(entity, "CassandraPersistentEntity must not be null");

		CreateTableSpecification specification = createTable(entity.getTableName());

		for (CassandraPersistentProperty property : entity) {

			if (property.isCompositePrimaryKey()) {

				CassandraPersistentEntity<?> primaryKeyEntity = getRequiredPersistentEntity(property.getRawType());

				for (CassandraPersistentProperty primaryKeyProperty : primaryKeyEntity) {
					if (primaryKeyProperty.isPartitionKeyColumn()) {
						specification.partitionKeyColumn(primaryKeyProperty.getColumnName(),
								getDataType(primaryKeyProperty));
					} else { // cluster column
						specification.clusteredKeyColumn(primaryKeyProperty.getColumnName(),
								getDataType(primaryKeyProperty), primaryKeyProperty.getPrimaryKeyOrdering());
					}
				}
			}
			else {
				if (property.isIdProperty() || property.isPartitionKeyColumn()) {
					specification.partitionKeyColumn(property.getColumnName(),
							UserTypeUtil.potentiallyFreeze(getDataType(property)));
				} else if (property.isClusterKeyColumn()) {
					specification.clusteredKeyColumn(property.getColumnName(),
							UserTypeUtil.potentiallyFreeze(getDataType(property)), property.getPrimaryKeyOrdering());
				} else {
					specification.column(property.getColumnName(),
							UserTypeUtil.potentiallyFreeze(getDataType(property)));
				}
			}
		}

		if (specification.getPartitionKeyColumns().isEmpty()) {
			throw new MappingException(String.format("No partition key columns found in entity [%s]", entity.getType()));
		}

		return specification;
	}

	/**
	 * @param entity must not be {@literal null}.
	 * @return
	 * @since 2.0
	 */
	public List<CreateIndexSpecification> getCreateIndexSpecificationsFor(CassandraPersistentEntity<?> entity) {

		Assert.notNull(entity, "CassandraPersistentEntity must not be null");

		List<CreateIndexSpecification> indexes = new ArrayList<>();

		for (CassandraPersistentProperty property : entity) {
			if (property.isCompositePrimaryKey()) {
				indexes.addAll(getCreateIndexSpecificationsFor(getRequiredPersistentEntity(property)));
			} else {
				indexes.addAll(IndexSpecificationFactory.createIndexSpecifications(property));
			}
		}

		indexes.forEach(it -> it.tableName(entity.getTableName()));

		return indexes;
	}

	/**
	 * Returns a {@link CreateUserTypeSpecification} for the given entity, including all mapping information.
	 *
	 * @param entity must not be {@literal null}.
	 */
	public CreateUserTypeSpecification getCreateUserTypeSpecificationFor(CassandraPersistentEntity<?> entity) {

		Assert.notNull(entity, "CassandraPersistentEntity must not be null");

		CreateUserTypeSpecification specification = CreateUserTypeSpecification.createType(entity.getTableName());

		for (CassandraPersistentProperty property : entity) {
			// Use frozen literal to not resolve types from Cassandra; At this stage, they might be not created yet.
			specification.field(property.getColumnName(),
				getDataTypeWithUserTypeFactory(property, DataTypeProvider.FrozenLiteral));
		}

		if (specification.getFields().isEmpty()) {
			throw new MappingException(String.format("No fields in user type [%s]", entity.getType()));
		}

		return specification;
	}

	/**
	 * Retrieve the data type based on the given {@code type}. Cassandra {@link DataType types} are determined using
	 * simple types and configured {@link org.springframework.data.convert.CustomConversions}.
	 *
	 * @param type must not be {@literal null}.
	 * @return the Cassandra {@link DataType type}.
	 * @see org.springframework.data.convert.CustomConversions
	 * @see CassandraSimpleTypeHolder
	 * @since 1.5
	 */
	public DataType getDataType(Class<?> type) {

		return this.customConversions.getCustomWriteTarget(type)
				.map(CassandraSimpleTypeHolder::getDataTypeFor)
				.orElseGet(() -> getDataTypeFor(type));
	}

	/**
	 * Retrieve the data type of the property. Cassandra {@link DataType types} are determined using simple types and
	 * configured {@link org.springframework.data.convert.CustomConversions}.
	 *
	 * @param property must not be {@literal null}.
	 * @return the Cassandra {@link DataType type}.
	 * @see org.springframework.data.convert.CustomConversions
	 * @see CassandraSimpleTypeHolder
	 * @since 1.5
	 */
	public DataType getDataType(CassandraPersistentProperty property) {
		return getDataTypeWithUserTypeFactory(property, DataTypeProvider.EntityUserType);
	}

	private DataType getDataTypeWithUserTypeFactory(CassandraPersistentProperty property,
			DataTypeProvider dataTypeProvider) {

		if (property.isAnnotationPresent(CassandraType.class)) {

			CassandraType annotation = property.getRequiredAnnotation(CassandraType.class);

			if (annotation.type() == Name.TUPLE) {

				DataType[] dataTypes = Arrays.stream(annotation.typeArguments())
					.map(CassandraSimpleTypeHolder::getDataTypeFor)
					.toArray(DataType[]::new);

				return TupleType.of(ProtocolVersion.NEWEST_SUPPORTED, CodecRegistry.DEFAULT_INSTANCE, dataTypes);
			}

			if (annotation.type() == Name.UDT) {

				CqlIdentifier userTypeName = CqlIdentifier.of(annotation.userTypeName());

				DataType userType = dataTypeProvider.getUserType(userTypeName, resolveUserTypeResolver());

				if (userType == null) {
					throw new MappingException(String.format("User type [%s] not found", userTypeName));
				}

				DataType dataType = getUserDataType(property.getTypeInformation(), userType);

				if (dataType != null) {
					return dataType;
				}
			}

			return property.getDataType();
		}

		return getDataTypeWithUserTypeFactory(property.getTypeInformation(), dataTypeProvider, property::getDataType);
	}

	private DataType getDataTypeWithUserTypeFactory(TypeInformation<?> typeInformation,
			DataTypeProvider dataTypeProvider, Supplier<DataType> fallback) {

		BasicCassandraPersistentEntity<?> persistentEntity =
				getPersistentEntity(typeInformation.getRequiredActualType());

		if (persistentEntity != null && persistentEntity.isUserDefinedType()) {

			DataType dataType = getUserDataType(typeInformation, dataTypeProvider.getDataType(persistentEntity));

			if (dataType != null) {
				return dataType;
			}
		}

		Optional<DataType> customWriteTarget = this.customConversions
				.getCustomWriteTarget(typeInformation.getType())
				.map(CassandraSimpleTypeHolder::getDataTypeFor);

		DataType dataType = customWriteTarget.orElseGet(() ->
				this.customConversions.getCustomWriteTarget(typeInformation.getRequiredActualType().getType())
					.filter(it -> !typeInformation.isMap())
					.map(it -> {

						if (typeInformation.isCollectionLike()) {
							if (List.class.isAssignableFrom(typeInformation.getType())) {
								return DataType.list(getDataTypeFor(it));
							}

							if (Set.class.isAssignableFrom(typeInformation.getType())) {
								return DataType.set(getDataTypeFor(it));
							}
						}

						return getDataTypeFor(it);

					}).orElse(null));

		return dataType != null ? dataType
			: typeInformation.isMap() ? getMapDataType(typeInformation, dataTypeProvider) : fallback.get();
	}

	@SuppressWarnings("all")
	private DataType getMapDataType(TypeInformation<?> typeInformation, DataTypeProvider dataTypeProvider) {

		TypeInformation<?> keyTypeInformation = typeInformation.getComponentType();
		TypeInformation<?> valueTypeInformation = typeInformation.getMapValueType();

		DataType keyType = getDataTypeWithUserTypeFactory(keyTypeInformation, dataTypeProvider, () -> {

			DataType type = getDataTypeFor(keyTypeInformation.getType());

			if (type != null) {
				return type;
			}

			throw new MappingException(String.format("Cannot resolve key type for [%s]", typeInformation));
		});

		DataType valueType = getDataTypeWithUserTypeFactory(valueTypeInformation, dataTypeProvider, () -> {

			DataType type = getDataTypeFor(valueTypeInformation.getType());

			if (type != null) {
				return type;
			}

			throw new MappingException("Cannot resolve value type for " + typeInformation + ".");
		});

		return DataType.map(keyType, valueType);
	}

	@Nullable
	private DataType getUserDataType(TypeInformation<?> property, DataType elementType) {

		if (property.isCollectionLike()) {

			if (List.class.isAssignableFrom(property.getType())) {
				return DataType.list(elementType);
			}

			if (Set.class.isAssignableFrom(property.getType())) {
				return DataType.set(elementType);
			}
		}

		return !(property.isCollectionLike() || property.isMap()) ? elementType : null;
	}

	/**
	 * @author Jens Schauder
	 * @author Mark Paluch
	 * @since 1.5.1
	 */
	enum DataTypeProvider {

		EntityUserType {

			@Override
			public DataType getDataType(CassandraPersistentEntity<?> entity) {
				return entity.getUserType();
			}

			@Override
			DataType getUserType(CqlIdentifier userTypeName, UserTypeResolver userTypeResolver) {
				return userTypeResolver.resolveType(userTypeName);
			}
		},

		FrozenLiteral {

			@Override
			public DataType getDataType(CassandraPersistentEntity<?> entity) {
				return new FrozenLiteralDataType(entity.getTableName());
			}

			@Override
			DataType getUserType(CqlIdentifier userTypeName, UserTypeResolver userTypeResolver) {
				return new FrozenLiteralDataType(userTypeName);
			}
		};

		/**
		 * Return the data type for the {@link CassandraPersistentEntity}.
		 *
		 * @param entity must not be {@literal null}.
		 * @return the {@link DataType}.
		 */
		@Nullable
		abstract DataType getDataType(CassandraPersistentEntity<?> entity);

		/**
		 * Return the user-defined type {@code userTypeName}.
		 *
		 * @param userTypeName must not be {@literal null}.
		 * @param userTypeResolver must not be {@literal null}.
		 * @return the {@link DataType}.
		 * @since 2.0.1
		 */
		@Nullable
		abstract DataType getUserType(CqlIdentifier userTypeName, UserTypeResolver userTypeResolver);

	}
}
