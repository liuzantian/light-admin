package org.lightadmin.core.rest;

import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.Lists;
import org.lightadmin.core.config.bootstrap.parsing.configuration.DomainConfigurationUnitType;
import org.lightadmin.core.config.domain.DomainTypeAdministrationConfiguration;
import org.lightadmin.core.config.domain.GlobalAdministrationConfiguration;
import org.lightadmin.core.config.domain.GlobalAdministrationConfigurationAware;
import org.lightadmin.core.config.domain.field.FieldMetadata;
import org.lightadmin.core.config.domain.scope.ScopeMetadata;
import org.lightadmin.core.config.domain.scope.ScopeMetadataUtils;
import org.lightadmin.core.persistence.metamodel.DomainTypeAttributeMetadata;
import org.lightadmin.core.persistence.metamodel.DomainTypeEntityMetadata;
import org.lightadmin.core.persistence.repository.DynamicJpaRepository;
import org.lightadmin.core.search.SpecificationCreator;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.domain.Specifications;
import org.springframework.data.rest.repository.RepositoryConstraintViolationException;
import org.springframework.data.rest.webmvc.PagingAndSorting;
import org.springframework.data.rest.webmvc.RepositoryRestController;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.PagedResources;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.util.ReflectionUtils;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.UndeclaredThrowableException;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Lists.newLinkedList;
import static com.google.common.collect.Maps.newHashMap;

@SuppressWarnings( "unchecked" )
@RequestMapping( "/rest" )
public class DynamicRepositoryRestController extends RepositoryRestController implements GlobalAdministrationConfigurationAware {

	private final SpecificationCreator specificationCreator = new SpecificationCreator();

	private GlobalAdministrationConfiguration configuration;

    private ApplicationContext applicationContext;

	@ResponseBody
	@RequestMapping( value = "/{repositoryName}/{id}/unit/{configurationUnit}", method = RequestMethod.GET )
	public ResponseEntity<?> entity(ServletServerHttpRequest request, URI baseUri, @PathVariable String repositoryName, @PathVariable String id, @PathVariable String configurationUnit) throws IOException {

		final DomainTypeAdministrationConfiguration domainTypeAdministrationConfiguration = configuration.forEntityName( repositoryName );

		final DomainTypeEntityMetadata domainTypeEntityMetadata = domainTypeAdministrationConfiguration.getDomainTypeEntityMetadata();
		final DynamicJpaRepository repository = domainTypeAdministrationConfiguration.getRepository();

		Serializable entityId = _stringToSerializable( id, ( Class<? extends Serializable> ) domainTypeEntityMetadata.getIdAttribute().getType() );

		final Object entity = repository.findOne( entityId );

		return _negotiateResponse( request, HttpStatus.OK, new HttpHeaders(), new DomainTypeResource( entity, fields( configurationUnit, domainTypeAdministrationConfiguration ) ) );
	}

	@ResponseBody
	@RequestMapping( value = "/{repositoryName}/scope/{scopeName}/search", method = RequestMethod.GET )
	public ResponseEntity<?> filterEntities( ServletServerHttpRequest request, @SuppressWarnings( "unused" ) URI baseUri, PagingAndSorting pageSort, @PathVariable String repositoryName, @PathVariable String scopeName ) throws IOException {

		final DomainTypeAdministrationConfiguration domainTypeAdministrationConfiguration = configuration.forEntityName( repositoryName );

		final DomainTypeEntityMetadata domainTypeEntityMetadata = domainTypeAdministrationConfiguration.getDomainTypeEntityMetadata();
		final DynamicJpaRepository repository = domainTypeAdministrationConfiguration.getRepository();

		final ScopeMetadata scope = domainTypeAdministrationConfiguration.getScopes().getScope( scopeName );

		final Specification filterSpecification = specificationFromRequest( request, domainTypeEntityMetadata );

		Set<FieldMetadata> listViewFields = domainTypeAdministrationConfiguration.getListViewFragment().getFields();

		if ( isPredicateScope( scope ) ) {
			final ScopeMetadataUtils.PredicateScopeMetadata predicateScope = ( ScopeMetadataUtils.PredicateScopeMetadata ) scope;

			final Page page = findBySpecificationAndPredicate( repository, filterSpecification, predicateScope.predicate(), pageSort );

			return negotiateResponse( request, page, pageMetadata( page ), listViewFields );
		}

		if ( isSpecificationScope( scope ) ) {
			final Specification scopeSpecification = ( ( ScopeMetadataUtils.SpecificationScopeMetadata ) scope ).specification();

			Page page = findItemsBySpecification( repository, and( scopeSpecification, filterSpecification ), pageSort );

			return negotiateResponse( request, page, pageMetadata( page ), listViewFields );
		}

		Page page = findItemsBySpecification( repository, filterSpecification, pageSort );

		return negotiateResponse( request, page, pageMetadata( page ), listViewFields );
	}

	@ExceptionHandler(RepositoryConstraintViolationException.class)
	@ResponseBody
	public ResponseEntity handleValidationFailure(RepositoryConstraintViolationException ex, ServletServerHttpRequest request) throws IOException {
		final Map packet = newHashMap();
		final List<Map<String, String>> errors = newArrayList();

		for (FieldError fe : ex.getErrors().getFieldErrors()) {
			List<Object> args = newArrayList(fe.getObjectName(), fe.getField(), fe.getRejectedValue());

			if (null != fe.getArguments()) {
				Collections.addAll( args, fe.getArguments() );
			}

			String msg = applicationContext.getMessage(fe.getCode(), args.toArray(), fe.getDefaultMessage(), null);
			Map<String, String> error = newHashMap();
			error.put("field", fe.getField());
			error.put("message", msg);
			errors.add(error);
		}
		packet.put("errors", errors);

		return _negotiateResponse(request, HttpStatus.BAD_REQUEST, new HttpHeaders(), packet);
	}

	private Set<FieldMetadata> fields( String configurationUnit, DomainTypeAdministrationConfiguration domainTypeAdministrationConfiguration ) {
		final DomainConfigurationUnitType configurationUnitType = DomainConfigurationUnitType.forName( configurationUnit );

		switch ( configurationUnitType ) {
			case SHOW_VIEW:
				return domainTypeAdministrationConfiguration.getShowViewFragment().getFields();
			case FORM_VIEW:
				return domainTypeAdministrationConfiguration.getShowViewFragment().getFields();
			case QUICK_VIEW:
				return domainTypeAdministrationConfiguration.getShowViewFragment().getFields();
			default:
				return domainTypeAdministrationConfiguration.getShowViewFragment().getFields();
		}
	}

	private Page findBySpecificationAndPredicate( DynamicJpaRepository repository, final Specification specification, Predicate predicate, final PagingAndSorting pageSort ) {
		final List<?> items = findItemsBySpecification( repository, specification, pageSort.getSort() );

		return selectPage( newArrayList( Collections2.filter( items, predicate ) ), pageSort );
	}

	private Page<?> findItemsBySpecification( final DynamicJpaRepository repository, final Specification specification, final PagingAndSorting pageSort ) {
		return repository.findAll( specification, pageSort );
	}

	private List<?> findItemsBySpecification( final DynamicJpaRepository repository, final Specification specification, final Sort sort ) {
		return repository.findAll( specification, sort );
	}

	private Page<?> selectPage( List<Object> items, PagingAndSorting pageSort ) {
		final List<Object> itemsOnPage = items.subList( pageSort.getOffset(), Math.min( items.size(), pageSort.getOffset() + pageSort.getPageSize() ) );

		return new PageImpl<Object>( itemsOnPage, pageSort, items.size() );
	}

	private boolean isSpecificationScope( final ScopeMetadata scope ) {
		return scope instanceof ScopeMetadataUtils.SpecificationScopeMetadata;
	}

	private boolean isPredicateScope( final ScopeMetadata scope ) {
		return scope instanceof ScopeMetadataUtils.PredicateScopeMetadata;
	}

	private Specification and( Specification specification, Specification otherSpecification ) {
		return Specifications.where( specification ).and( otherSpecification );
	}

	private Specification specificationFromRequest( ServletServerHttpRequest request, final DomainTypeEntityMetadata<? extends DomainTypeAttributeMetadata> entityMetadata ) {
		final Map<String, String[]> parameters = request.getServletRequest().getParameterMap();

		return specificationCreator.toSpecification( entityMetadata, parameters );
	}

	private <V extends Serializable> V _stringToSerializable(String str, Class<V> targetType) {
		Method stringToSerializableMethod = ReflectionUtils.findMethod( getClass(), "stringToSerializable", String.class, Class.class );

		ReflectionUtils.makeAccessible( stringToSerializableMethod );

		try {
			return ( V ) stringToSerializableMethod.invoke( this, str, targetType);
		} catch ( InvocationTargetException ex ) {
			ReflectionUtils.rethrowRuntimeException( ex.getTargetException() );
			return null; // :)
		} catch ( IllegalAccessException ex ) {
			throw new UndeclaredThrowableException( ex );
		}
	}

	private ResponseEntity<byte[]> _negotiateResponse(final ServletServerHttpRequest request, final HttpStatus status, final HttpHeaders headers, final Object resource) throws IOException {
		Method negotiateResponseMethod = ReflectionUtils.findMethod( getClass(), "negotiateResponse", ServletServerHttpRequest.class, HttpStatus.class, HttpHeaders.class, Object.class );

		ReflectionUtils.makeAccessible( negotiateResponseMethod );

		try {
			return ( ResponseEntity<byte[]> ) negotiateResponseMethod.invoke( this, request, status, headers, resource );
		} catch ( InvocationTargetException ex ) {
			ReflectionUtils.rethrowRuntimeException( ex.getTargetException() );
			return null; // :)
		} catch ( IllegalAccessException ex ) {
			throw new UndeclaredThrowableException( ex );
		}
	}

	private ResponseEntity<?> negotiateResponse( ServletServerHttpRequest request, Page page, PagedResources.PageMetadata pageMetadata, Set<FieldMetadata> fieldMetadatas ) throws IOException {
		return _negotiateResponse( request, HttpStatus.OK, new HttpHeaders(), new PagedResources( toResources( page, fieldMetadatas ), pageMetadata, Lists.<Link>newArrayList() ) );
	}

	private PagedResources.PageMetadata pageMetadata( final Page page ) {
		return new PagedResources.PageMetadata( page.getSize(), page.getNumber() + 1, page.getTotalElements(), page.getTotalPages() );
	}

	private List<Object> toResources( Page page, Set<FieldMetadata> fieldMetadatas ) {
		if ( !page.hasContent() ) {
			return newLinkedList();
		}

		List<Object> allResources = newArrayList();
		for ( final Object item : page ) {
			allResources.add( new DomainTypeResource( item, fieldMetadatas ) );
		}
		return allResources;
	}

	@Override
	@Autowired
	public void setGlobalAdministrationConfiguration( final GlobalAdministrationConfiguration configuration ) {
		this.configuration = configuration;
	}

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        super.setApplicationContext(applicationContext);
        this.applicationContext = applicationContext;
    }
}