/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *  
 *    http://www.apache.org/licenses/LICENSE-2.0
 *  
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License. 
 *  
 */
package org.apache.directory.server.core.authz;


import java.text.ParseException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;

import org.apache.directory.server.core.DirectoryServiceConfiguration;
import org.apache.directory.server.core.authn.LdapPrincipal;
import org.apache.directory.server.core.authz.support.ACDFEngine;
import org.apache.directory.server.core.configuration.InterceptorConfiguration;
import org.apache.directory.server.core.configuration.StartupConfiguration;
import org.apache.directory.server.core.enumeration.SearchResultFilter;
import org.apache.directory.server.core.enumeration.SearchResultFilteringEnumeration;
import org.apache.directory.server.core.interceptor.BaseInterceptor;
import org.apache.directory.server.core.interceptor.InterceptorChain;
import org.apache.directory.server.core.interceptor.NextInterceptor;
import org.apache.directory.server.core.interceptor.context.AddOperationContext;
import org.apache.directory.server.core.interceptor.context.CompareOperationContext;
import org.apache.directory.server.core.interceptor.context.LookupOperationContext;
import org.apache.directory.server.core.interceptor.context.ModifyOperationContext;
import org.apache.directory.server.core.interceptor.context.MoveAndRenameOperationContext;
import org.apache.directory.server.core.interceptor.context.MoveOperationContext;
import org.apache.directory.server.core.interceptor.context.OperationContext;
import org.apache.directory.server.core.interceptor.context.RenameOperationContext;
import org.apache.directory.server.core.interceptor.context.SearchOperationContext;
import org.apache.directory.server.core.invocation.Invocation;
import org.apache.directory.server.core.invocation.InvocationStack;
import org.apache.directory.server.core.jndi.ServerContext;
import org.apache.directory.server.core.jndi.ServerLdapContext;
import org.apache.directory.server.core.partition.PartitionNexusProxy;
import org.apache.directory.server.core.subtree.SubentryService;
import org.apache.directory.server.schema.ConcreteNameComponentNormalizer;
import org.apache.directory.server.schema.registries.AttributeTypeRegistry;
import org.apache.directory.server.schema.registries.OidRegistry;
import org.apache.directory.shared.ldap.aci.ACIItem;
import org.apache.directory.shared.ldap.aci.ACIItemParser;
import org.apache.directory.shared.ldap.aci.ACITuple;
import org.apache.directory.shared.ldap.aci.MicroOperation;
import org.apache.directory.shared.ldap.constants.SchemaConstants;
import org.apache.directory.shared.ldap.exception.LdapNamingException;
import org.apache.directory.shared.ldap.message.ModificationItemImpl;
import org.apache.directory.shared.ldap.message.ResultCodeEnum;
import org.apache.directory.shared.ldap.name.LdapDN;
import org.apache.directory.shared.ldap.schema.AttributeType;
import org.apache.directory.shared.ldap.util.AttributeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * An ACI based authorization service.
 *
 * @author <a href="mailto:dev@directory.apache.org">Apache Directory Project</a>
 * @version $Rev$
 */
public class AuthorizationService extends BaseInterceptor
{
    /** the logger for this class */
    private static final Logger log = LoggerFactory.getLogger( AuthorizationService.class );

    /**
     * the multivalued op attr used to track the perscriptive access control
     * subentries that apply to an entry.
     */
    private static final String AC_SUBENTRY_ATTR = "accessControlSubentries";

    private static final Collection<MicroOperation> ADD_PERMS;
    private static final Collection<MicroOperation> READ_PERMS;
    private static final Collection<MicroOperation> COMPARE_PERMS;
    private static final Collection<MicroOperation> SEARCH_ENTRY_PERMS;
    private static final Collection<MicroOperation> SEARCH_ATTRVAL_PERMS;
    private static final Collection<MicroOperation> REMOVE_PERMS;
    private static final Collection<MicroOperation> MATCHEDNAME_PERMS;
    private static final Collection<MicroOperation> BROWSE_PERMS;
    private static final Collection<MicroOperation> LOOKUP_PERMS;
    private static final Collection<MicroOperation> REPLACE_PERMS;
    private static final Collection<MicroOperation> RENAME_PERMS;
    private static final Collection<MicroOperation> EXPORT_PERMS;
    private static final Collection<MicroOperation> IMPORT_PERMS;
    private static final Collection<MicroOperation> MOVERENAME_PERMS;

    static
    {
        Set<MicroOperation> set = new HashSet<MicroOperation>( 2 );
        set.add( MicroOperation.BROWSE );
        set.add( MicroOperation.RETURN_DN );
        SEARCH_ENTRY_PERMS = Collections.unmodifiableCollection( set );

        set = new HashSet<MicroOperation>( 2 );
        set.add( MicroOperation.READ );
        set.add( MicroOperation.BROWSE );
        LOOKUP_PERMS = Collections.unmodifiableCollection( set );

        set = new HashSet<MicroOperation>( 2 );
        set.add( MicroOperation.ADD );
        set.add( MicroOperation.REMOVE );
        REPLACE_PERMS = Collections.unmodifiableCollection( set );

        set = new HashSet<MicroOperation>( 2 );
        set.add( MicroOperation.EXPORT );
        set.add( MicroOperation.RENAME );
        MOVERENAME_PERMS = Collections.unmodifiableCollection( set );

        SEARCH_ATTRVAL_PERMS = Collections.singleton( MicroOperation.READ );
        ADD_PERMS = Collections.singleton( MicroOperation.ADD );
        READ_PERMS = Collections.singleton( MicroOperation.READ );
        COMPARE_PERMS = Collections.singleton( MicroOperation.COMPARE );
        REMOVE_PERMS = Collections.singleton( MicroOperation.REMOVE );
        MATCHEDNAME_PERMS = Collections.singleton( MicroOperation.DISCLOSE_ON_ERROR );
        BROWSE_PERMS = Collections.singleton( MicroOperation.BROWSE );
        RENAME_PERMS = Collections.singleton( MicroOperation.RENAME );
        EXPORT_PERMS = Collections.singleton( MicroOperation.EXPORT );
        IMPORT_PERMS = Collections.singleton( MicroOperation.IMPORT );
    }

    /** a tupleCache that responds to add, delete, and modify attempts */
    private TupleCache tupleCache;
    
    /** a groupCache that responds to add, delete, and modify attempts */
    private GroupCache groupCache;
    
    /** a normalizing ACIItem parser */
    private ACIItemParser aciParser;
    
    /** use and instance of the ACDF engine */
    private ACDFEngine engine;
    
    /** interceptor chain */
    private InterceptorChain chain;
    
    /** attribute type registry */
    private AttributeTypeRegistry attrRegistry;
    
    /** whether or not this interceptor is activated */
    private boolean enabled = false;
    
    /** the system wide subschemaSubentryDn */
    private String subschemaSubentryDn;

    private AttributeType objectClassType;
    private AttributeType acSubentryType;
    
    private String objectClassOid;
    private String subentryOid;
    private String acSubentryOid;

    /** A storage for the entryACI attributeType */
    private AttributeType entryAciType;

    /** the subentry ACI attribute type */
    private AttributeType subentryAciType;
    
    public static final SearchControls DEFAULT_SEARCH_CONTROLS = new SearchControls();

    /**
     * Initializes this interceptor based service by getting a handle on the nexus, setting up
     * the tupe and group membership caches and the ACIItem parser and the ACDF engine.
     *
     * @param factoryCfg the ContextFactory configuration for the server
     * @param cfg the interceptor configuration
     * @throws NamingException if there are problems during initialization
     */
    public void init( DirectoryServiceConfiguration factoryCfg, InterceptorConfiguration cfg ) throws NamingException
    {
        super.init( factoryCfg, cfg );
        tupleCache = new TupleCache( factoryCfg );
        groupCache = new GroupCache( factoryCfg );
        attrRegistry = factoryCfg.getRegistries().getAttributeTypeRegistry();
        OidRegistry oidRegistry = factoryCfg.getRegistries().getOidRegistry();
        
        // look up some constant information
        objectClassOid = oidRegistry.getOid( SchemaConstants.OBJECT_CLASS_AT );
        subentryOid = oidRegistry.getOid( SchemaConstants.SUBENTRY_OC );
        acSubentryOid = oidRegistry.getOid( AC_SUBENTRY_ATTR );
        objectClassType = attrRegistry.lookup( objectClassOid );
        acSubentryType = attrRegistry.lookup( acSubentryOid );
        entryAciType = attrRegistry.lookup( SchemaConstants.ENTRY_ACI_AT_OID ); 
        subentryAciType = attrRegistry.lookup( SchemaConstants.SUBENTRY_ACI_AT_OID );
        
        aciParser = new ACIItemParser( new ConcreteNameComponentNormalizer( attrRegistry, oidRegistry ), attrRegistry.getNormalizerMapping() );
        engine = new ACDFEngine( factoryCfg.getRegistries().getOidRegistry(), attrRegistry );
        chain = factoryCfg.getInterceptorChain();
        enabled = factoryCfg.getStartupConfiguration().isAccessControlEnabled();

        // stuff for dealing with subentries (garbage for now)
        String subschemaSubentry = 
        	( String ) factoryCfg.getPartitionNexus().getRootDSE( null ).
        		get( SchemaConstants.SUBSCHEMA_SUBENTRY_AT ).get();
        LdapDN subschemaSubentryDnName = new LdapDN( subschemaSubentry );
        subschemaSubentryDnName.normalize( attrRegistry.getNormalizerMapping() );
        subschemaSubentryDn = subschemaSubentryDnName.toNormName();
    }


    private LdapDN parseNormalized( String name ) throws NamingException
    {
        LdapDN dn = new LdapDN( name );
        dn.normalize( attrRegistry.getNormalizerMapping() );
        return dn;
    }


    /**
     * Adds perscriptiveACI tuples to a collection of tuples by accessing the
     * tupleCache.  The tuple cache is accessed for each A/C subentry
     * associated with the protected entry.  Note that subentries are handled
     * differently: their parent, the administrative entry is accessed to
     * determine the perscriptiveACIs effecting the AP and hence the subentry
     * which is considered to be in the same context.
     *
     * @param tuples the collection of tuples to add to
     * @param dn the normalized distinguished name of the protected entry
     * @param entry the target entry that access to is being controled
     * @throws NamingException if there are problems accessing attribute values
     */
    private void addPerscriptiveAciTuples( PartitionNexusProxy proxy, Collection<ACITuple> tuples, LdapDN dn,
        Attributes entry ) throws NamingException
    {
        Attribute oc = AttributeUtils.getAttribute( entry, objectClassType );
        
        /*
         * If the protected entry is a subentry, then the entry being evaluated
         * for perscriptiveACIs is in fact the administrative entry.  By
         * substituting the administrative entry for the actual subentry the
         * code below this "if" statement correctly evaluates the effects of
         * perscriptiveACI on the subentry.  Basically subentries are considered
         * to be in the same naming context as their access point so the subentries
         * effecting their parent entry applies to them as well.
         */
        if ( AttributeUtils.containsValue( oc, SchemaConstants.SUBENTRY_OC, objectClassType ) || 
             AttributeUtils.containsValue( oc, subentryOid, objectClassType ) )
        {
            LdapDN parentDn = ( LdapDN ) dn.clone();
            parentDn.remove( dn.size() - 1 );
            entry = proxy.lookup( new LookupOperationContext( parentDn), PartitionNexusProxy.LOOKUP_BYPASS );
        }

        Attribute subentries = AttributeUtils.getAttribute( entry, acSubentryType );
        
        if ( subentries == null )
        {
            return;
        }
        
        for ( int ii = 0; ii < subentries.size(); ii++ )
        {
            String subentryDn = ( String ) subentries.get( ii );
            tuples.addAll( tupleCache.getACITuples( subentryDn ) );
        }
    }


    /**
     * Adds the set of entryACI tuples to a collection of tuples.  The entryACI
     * is parsed and tuples are generated on they fly then added to the collection.
     *
     * @param tuples the collection of tuples to add to
     * @param entry the target entry that access to is being regulated
     * @throws NamingException if there are problems accessing attribute values
     */
    private void addEntryAciTuples( Collection<ACITuple> tuples, Attributes entry ) throws NamingException
    {
        Attribute entryAci = AttributeUtils.getAttribute( entry, entryAciType );
        
        if ( entryAci == null )
        {
            return;
        }

        for ( int ii = 0; ii < entryAci.size(); ii++ )
        {
            String aciString = ( String ) entryAci.get( ii );
            ACIItem item;

            try
            {
                item = aciParser.parse( aciString );
            }
            catch ( ParseException e )
            {
                String msg = "failed to parse entryACI: " + aciString;
                log.error( msg, e );
                throw new LdapNamingException( msg, ResultCodeEnum.OPERATIONS_ERROR );
            }

            tuples.addAll( item.toTuples() );
        }
    }


    /**
     * Adds the set of subentryACI tuples to a collection of tuples.  The subentryACI
     * is parsed and tuples are generated on the fly then added to the collection.
     *
     * @param tuples the collection of tuples to add to
     * @param dn the normalized distinguished name of the protected entry
     * @param entry the target entry that access to is being regulated
     * @throws NamingException if there are problems accessing attribute values
     */
    private void addSubentryAciTuples( PartitionNexusProxy proxy, Collection<ACITuple> tuples, LdapDN dn, Attributes entry )
        throws NamingException
    {
        // only perform this for subentries
        if ( !AttributeUtils.containsValueCaseIgnore( entry.get( SchemaConstants.OBJECT_CLASS_AT ), SchemaConstants.SUBENTRY_OC ) )
        {
            return;
        }

        // get the parent or administrative entry for this subentry since it
        // will contain the subentryACI attributes that effect subentries
        LdapDN parentDn = ( LdapDN ) dn.clone();
        parentDn.remove( dn.size() - 1 );
        Attributes administrativeEntry = proxy.lookup( 
        		new LookupOperationContext( parentDn, new String[]
            { SchemaConstants.SUBENTRY_ACI_AT }) , PartitionNexusProxy.LOOKUP_BYPASS );
        Attribute subentryAci = AttributeUtils.getAttribute( administrativeEntry, subentryAciType );

        if ( subentryAci == null )
        {
            return;
        }

        for ( int ii = 0; ii < subentryAci.size(); ii++ )
        {
            String aciString = ( String ) subentryAci.get( ii );
            ACIItem item;

            try
            {
                item = aciParser.parse( aciString );
            }
            catch ( ParseException e )
            {
                String msg = "failed to parse subentryACI: " + aciString;
                log.error( msg, e );
                throw new LdapNamingException( msg, ResultCodeEnum.OPERATIONS_ERROR );
            }

            tuples.addAll( item.toTuples() );
        }
    }


    /* -------------------------------------------------------------------------------
     * Within every access controled interceptor method we must retrieve the ACITuple
     * set for all the perscriptiveACIs that apply to the candidate, the target entry
     * operated upon.  This ACITuple set is gotten from the TupleCache by looking up
     * the subentries referenced by the accessControlSubentries operational attribute
     * within the target entry.
     *
     * Then the entry is inspected for an entryACI.  This is not done for the add op
     * since it could introduce a security breech.  So for non-add ops if present a
     * set of ACITuples are generated for all the entryACIs within the entry.  This
     * set is combined with the ACITuples cached for the perscriptiveACI affecting
     * the target entry.  If the entry is a subentry the ACIs are also processed for
     * the subentry to generate more ACITuples.  This subentry TupleACI set is joined
     * with the entry and perscriptive ACI.
     *
     * The union of ACITuples are fed into the engine along with other parameters
     * to decide whether a permission is granted or rejected for the specific
     * operation.
     * -------------------------------------------------------------------------------
     */

    public void add( NextInterceptor next, OperationContext addContext ) throws NamingException
    {
        // Access the principal requesting the operation, and bypass checks if it is the admin
        Invocation invocation = InvocationStack.getInstance().peek();
        LdapPrincipal principal = ( ( ServerContext ) invocation.getCaller() ).getPrincipal();
        LdapDN principalDn = principal.getJndiName();
        
        Attributes entry = ((AddOperationContext)addContext).getEntry();
        LdapDN name = addContext.getDn();

        // bypass authz code if we are disabled
        if ( !enabled )
        {
            next.add( addContext );
            return;
        }

        // bypass authz code but manage caches if operation is performed by the admin
        if ( isPrincipalAnAdministrator( principalDn ) )
        {
            next.add( addContext );
            tupleCache.subentryAdded( name.getUpName(), name, entry );
            groupCache.groupAdded( name, entry );
            return;
        }

        // perform checks below here for all non-admin users
        SubentryService subentryService = ( SubentryService ) chain.get( StartupConfiguration.SUBENTRY_SERVICE_NAME );
        Attributes subentryAttrs = subentryService.getSubentryAttributes( name, entry );
        NamingEnumeration attrList = entry.getAll();
        
        while ( attrList.hasMore() )
        {
            subentryAttrs.put( ( Attribute ) attrList.next() );
        }

        // Assemble all the information required to make an access control decision
        Set userGroups = groupCache.getGroups( principalDn.toNormName() );
        Collection<ACITuple> tuples = new HashSet<ACITuple>();

        // Build the total collection of tuples to be considered for add rights
        // NOTE: entryACI are NOT considered in adds (it would be a security breech)
        addPerscriptiveAciTuples( invocation.getProxy(), tuples, name, subentryAttrs );
        addSubentryAciTuples( invocation.getProxy(), tuples, name, subentryAttrs );

        // check if entry scope permission is granted
        PartitionNexusProxy proxy = invocation.getProxy();
        engine.checkPermission( proxy, userGroups, principalDn, principal.getAuthenticationLevel(), name, null, null,
            ADD_PERMS, tuples, subentryAttrs );

        // now we must check if attribute type and value scope permission is granted
        NamingEnumeration attributeList = entry.getAll();
        
        while ( attributeList.hasMore() )
        {
            Attribute attr = ( Attribute ) attributeList.next();
        
            for ( int ii = 0; ii < attr.size(); ii++ )
            {
                engine.checkPermission( proxy, userGroups, principalDn, principal.getAuthenticationLevel(), name, attr
                    .getID(), attr.get( ii ), ADD_PERMS, tuples, entry );
            }
        }

        // if we've gotten this far then access has been granted
        next.add( addContext );

        // if the entry added is a subentry or a groupOf[Unique]Names we must
        // update the ACITuple cache and the groups cache to keep them in sync
        tupleCache.subentryAdded( name.getUpName(), name, entry );
        groupCache.groupAdded( name, entry );
    }


    public void delete( NextInterceptor next, OperationContext deleteContext ) throws NamingException
    {
    	LdapDN name = deleteContext.getDn();
    	
        // Access the principal requesting the operation, and bypass checks if it is the admin
        Invocation invocation = InvocationStack.getInstance().peek();
        PartitionNexusProxy proxy = invocation.getProxy();
        Attributes entry = proxy.lookup( new LookupOperationContext( name ) , PartitionNexusProxy.LOOKUP_BYPASS );
        LdapPrincipal principal = ( ( ServerContext ) invocation.getCaller() ).getPrincipal();
        LdapDN principalDn = principal.getJndiName();

        // bypass authz code if we are disabled
        if ( !enabled )
        {
            next.delete( deleteContext );
            return;
        }

        // bypass authz code but manage caches if operation is performed by the admin
        if ( isPrincipalAnAdministrator( principalDn ) )
        {
            next.delete( deleteContext );
            tupleCache.subentryDeleted( name, entry );
            groupCache.groupDeleted( name, entry );
            return;
        }

        Set userGroups = groupCache.getGroups( principalDn.toString() );
        Collection<ACITuple> tuples = new HashSet<ACITuple>();
        addPerscriptiveAciTuples( proxy, tuples, name, entry );
        addEntryAciTuples( tuples, entry );
        addSubentryAciTuples( proxy, tuples, name, entry );

        engine.checkPermission( proxy, userGroups, principalDn, principal.getAuthenticationLevel(), name, null, null,
            REMOVE_PERMS, tuples, entry );

        next.delete( deleteContext );
        tupleCache.subentryDeleted( name, entry );
        groupCache.groupDeleted( name, entry );
    }


    public void modify( NextInterceptor next, OperationContext opContext ) throws NamingException
    {
        // Access the principal requesting the operation, and bypass checks if it is the admin
        Invocation invocation = InvocationStack.getInstance().peek();
        PartitionNexusProxy proxy = invocation.getProxy();
        LdapDN name = opContext.getDn();

        // Access the principal requesting the operation, and bypass checks if it is the admin
        Attributes entry = proxy.lookup( new LookupOperationContext( name ), PartitionNexusProxy.LOOKUP_BYPASS );
        LdapPrincipal principal = ( ( ServerContext ) invocation.getCaller() ).getPrincipal();
        LdapDN principalDn = principal.getJndiName();

        // bypass authz code if we are disabled
        if ( !enabled )
        {
            next.modify( opContext );
            return;
        }

        ModificationItemImpl[] mods =((ModifyOperationContext)opContext).getModItems();

        // bypass authz code but manage caches if operation is performed by the admin
        if ( isPrincipalAnAdministrator( principalDn ) )
        {
            next.modify( opContext );
            /**
             * @TODO: A virtual entry can be created here for not hitting the backend again.
             */
            Attributes modifiedEntry = proxy.lookup( new LookupOperationContext( name ), PartitionNexusProxy.LOOKUP_BYPASS );
            tupleCache.subentryModified( name, mods, modifiedEntry );
            groupCache.groupModified( name, mods, entry );
            return;
        }

        Set userGroups = groupCache.getGroups( principalDn.toString() );
        Collection<ACITuple> tuples = new HashSet<ACITuple>();
        addPerscriptiveAciTuples( proxy, tuples, name, entry );
        addEntryAciTuples( tuples, entry );
        addSubentryAciTuples( proxy, tuples, name, entry );

        engine.checkPermission( proxy, userGroups, principalDn, principal.getAuthenticationLevel(), name, null, null,
            Collections.singleton( MicroOperation.MODIFY ), tuples, entry );

        Collection<MicroOperation> perms = null;

        for ( int ii = 0; ii < mods.length; ii++ )
        {
            Attribute attr = mods[ii].getAttribute();
            
            switch ( mods[ii].getModificationOp() )
            {
                case ( DirContext.ADD_ATTRIBUTE  ):
                    perms = ADD_PERMS;
                    // If the attribute is being created with an initial value ...
                    if ( entry.get( attr.getID() ) == null )
                    {
                        // ... we also need to check if adding the attribute is permitted
                        engine.checkPermission( proxy, userGroups, principalDn, principal.getAuthenticationLevel(), name,
                            attr.getID(), null, perms, tuples, entry );
                    }
                    break;
                    
                case ( DirContext.REMOVE_ATTRIBUTE  ):
                    perms = REMOVE_PERMS;
                    Attribute entryAttr = entry.get( attr.getID() );
                    if (  entryAttr != null )
                    {
                        // If there is only one value remaining in the attribute ...
                        if ( entryAttr.size() == 1 )
                        {
                            // ... we also need to check if removing the attribute at all is permitted
                            engine.checkPermission( proxy, userGroups, principalDn, principal.getAuthenticationLevel(), name,
                                attr.getID(), null, perms, tuples, entry );
                        }
                    }
                    break;
                    
                case ( DirContext.REPLACE_ATTRIBUTE  ):
                    perms = REPLACE_PERMS;
                    break;
            }

            for ( int jj = 0; jj < attr.size(); jj++ )
            {
                engine.checkPermission( proxy, userGroups, principalDn, principal.getAuthenticationLevel(), name,
                    attr.getID(), attr.get( jj ), perms, tuples, entry );
            }
        }

        

        next.modify( opContext );
        /**
         * @TODO: A virtual entry can be created here for not hitting the backend again.
         */
        Attributes modifiedEntry = proxy.lookup( new LookupOperationContext( name ), PartitionNexusProxy.LOOKUP_BYPASS );
        tupleCache.subentryModified( name, mods, modifiedEntry );
        groupCache.groupModified( name, mods, entry );
    }

    public boolean hasEntry( NextInterceptor next, OperationContext entryContext ) throws NamingException
    {
        LdapDN name = entryContext.getDn();
        Invocation invocation = InvocationStack.getInstance().peek();
        PartitionNexusProxy proxy = invocation.getProxy();
        Attributes entry = proxy.lookup( new LookupOperationContext( name ), PartitionNexusProxy.LOOKUP_BYPASS );
        LdapPrincipal principal = ( ( ServerContext ) invocation.getCaller() ).getPrincipal();
        LdapDN principalDn = principal.getJndiName();

        if ( isPrincipalAnAdministrator( principalDn ) || !enabled || ( name.size() == 0 ) ) // no checks on the rootdse
        {
            // No need to go down to the stack, if the dn is empty : it's the rootDSE, and it exists !
            if ( name.size() == 0 )
            {
                return true;
            }
            else
            {
                return next.hasEntry( entryContext );
            }
        }

        Set userGroups = groupCache.getGroups( principalDn.toNormName() );
        Collection<ACITuple> tuples = new HashSet<ACITuple>();
        addPerscriptiveAciTuples( proxy, tuples, name, entry );
        addEntryAciTuples( tuples, entry );
        addSubentryAciTuples( proxy, tuples, name, entry );

        // check that we have browse access to the entry
        engine.checkPermission( proxy, userGroups, principalDn, principal.getAuthenticationLevel(), name, null, null,
            BROWSE_PERMS, tuples, entry );

        return next.hasEntry( entryContext );
    }


    /**
     * Checks if the READ permissions exist to the entry and to each attribute type and
     * value.
     *
     * @todo not sure if we should hide attribute types/values or throw an exception
     * instead.  I think we're going to have to use a filter to restrict the return
     * of attribute types and values instead of throwing an exception.  Lack of read
     * perms to attributes and their values results in their removal when returning
     * the entry.
     *
     * @param principal the user associated with the call
     * @param dn the name of the entry being looked up
     * @param entry the raw entry pulled from the nexus
     * @throws NamingException
     */
    private void checkLookupAccess( LdapPrincipal principal, LdapDN dn, Attributes entry ) throws NamingException
    {
        // no permissions checks on the RootDSE
        if ( dn.toString().trim().equals( "" ) )
        {
            return;
        }

        PartitionNexusProxy proxy = InvocationStack.getInstance().peek().getProxy();
        LdapDN userName = principal.getJndiName();
        Set userGroups = groupCache.getGroups( userName.toNormName() );
        Collection<ACITuple> tuples = new HashSet<ACITuple>();
        addPerscriptiveAciTuples( proxy, tuples, dn, entry );
        addEntryAciTuples( tuples, entry );
        addSubentryAciTuples( proxy, tuples, dn, entry );

        // check that we have read access to the entry
        engine.checkPermission( proxy, userGroups, userName, principal.getAuthenticationLevel(), dn, null, null,
            LOOKUP_PERMS, tuples, entry );

        // check that we have read access to every attribute type and value
        NamingEnumeration attributeList = entry.getAll();
        while ( attributeList.hasMore() )
        {
            Attribute attr = ( Attribute ) attributeList.next();
            for ( int ii = 0; ii < attr.size(); ii++ )
            {
                engine.checkPermission( proxy, userGroups, userName, principal.getAuthenticationLevel(), dn, attr
                    .getID(), attr.get( ii ), READ_PERMS, tuples, entry );
            }
        }
    }


    public Attributes lookup( NextInterceptor next, OperationContext lookupContext ) throws NamingException
    {
        Invocation invocation = InvocationStack.getInstance().peek();
        LdapPrincipal principal = ( ( ServerContext ) invocation.getCaller() ).getPrincipal();
        LdapDN principalDn = principal.getJndiName();
        
        if ( !principalDn.isNormalized() )
        {
        	principalDn.normalize( attrRegistry.getNormalizerMapping() );
        }
        
        if ( isPrincipalAnAdministrator( principalDn ) || !enabled )
        {
            return next.lookup( lookupContext );
        }

        PartitionNexusProxy proxy = invocation.getProxy();
        Attributes entry = proxy.lookup( lookupContext, PartitionNexusProxy.LOOKUP_BYPASS );
        checkLookupAccess( principal, ((LookupOperationContext)lookupContext).getDn(), entry );
        return next.lookup( lookupContext );
    }

    public void rename( NextInterceptor next, OperationContext renameContext ) throws NamingException
    {
        LdapDN name = renameContext.getDn();
        String newRdn = ((RenameOperationContext)renameContext).getNewRdn();
        
        // Access the principal requesting the operation, and bypass checks if it is the admin
        Invocation invocation = InvocationStack.getInstance().peek();
        PartitionNexusProxy proxy = invocation.getProxy();
        Attributes entry = proxy.lookup( new LookupOperationContext( name ), PartitionNexusProxy.LOOKUP_BYPASS );
        LdapPrincipal principal = ( ( ServerContext ) invocation.getCaller() ).getPrincipal();
        LdapDN principalDn = principal.getJndiName();
        LdapDN newName = ( LdapDN ) name.clone();
        newName.remove( name.size() - 1 );
        newName.add( parseNormalized( newRdn ).get( 0 ) );

        // bypass authz code if we are disabled
        if ( !enabled )
        {
            next.rename( renameContext );
            return;
        }

        // bypass authz code but manage caches if operation is performed by the admin
        if ( isPrincipalAnAdministrator( principalDn ) )
        {
            next.rename( renameContext );
            tupleCache.subentryRenamed( name, newName );
            
            // TODO : this method returns a boolean : what should we do with the result ?
            groupCache.groupRenamed( name, newName );

            return;
        }

        Set userGroups = groupCache.getGroups( principalDn.toString() );
        Collection<ACITuple> tuples = new HashSet<ACITuple>();
        addPerscriptiveAciTuples( proxy, tuples, name, entry );
        addEntryAciTuples( tuples, entry );
        addSubentryAciTuples( proxy, tuples, name, entry );

        engine.checkPermission( proxy, userGroups, principalDn, principal.getAuthenticationLevel(), name, null, null,
            RENAME_PERMS, tuples, entry );

        next.rename( renameContext );
        tupleCache.subentryRenamed( name, newName );
        groupCache.groupRenamed( name, newName );
    }


    public void moveAndRename( NextInterceptor next, OperationContext moveAndRenameContext )
        throws NamingException
    {
        LdapDN oriChildName = moveAndRenameContext.getDn();
        LdapDN newParentName = ((MoveAndRenameOperationContext)moveAndRenameContext).getParent();
        String newRn = ((MoveAndRenameOperationContext)moveAndRenameContext).getNewRdn();
        
        // Access the principal requesting the operation, and bypass checks if it is the admin
        Invocation invocation = InvocationStack.getInstance().peek();
        PartitionNexusProxy proxy = invocation.getProxy();
        Attributes entry = proxy.lookup( new LookupOperationContext( oriChildName ), PartitionNexusProxy.LOOKUP_BYPASS );
        LdapPrincipal principal = ( ( ServerContext ) invocation.getCaller() ).getPrincipal();
        LdapDN principalDn = principal.getJndiName();
        LdapDN newName = ( LdapDN ) newParentName.clone();
        newName.add( newRn );

        // bypass authz code if we are disabled
        if ( !enabled )
        {
            next.moveAndRename( moveAndRenameContext );
            return;
        }

        // bypass authz code but manage caches if operation is performed by the admin
        if ( isPrincipalAnAdministrator( principalDn ) )
        {
            next.moveAndRename( moveAndRenameContext );
            tupleCache.subentryRenamed( oriChildName, newName );
            groupCache.groupRenamed( oriChildName, newName );
            return;
        }

        Set userGroups = groupCache.getGroups( principalDn.toString() );
        Collection<ACITuple> tuples = new HashSet<ACITuple>();
        addPerscriptiveAciTuples( proxy, tuples, oriChildName, entry );
        addEntryAciTuples( tuples, entry );
        addSubentryAciTuples( proxy, tuples, oriChildName, entry );

        engine.checkPermission( proxy, userGroups, principalDn, principal.getAuthenticationLevel(), oriChildName, null,
            null, MOVERENAME_PERMS, tuples, entry );

        // Get the entry again without operational attributes
        // because access control subentry operational attributes
        // will not be valid at the new location.
        // This will certainly be fixed by the SubentryService,
        // but after this service.
        Attributes importedEntry = proxy.lookup( new LookupOperationContext( oriChildName ), 
            PartitionNexusProxy.LOOKUP_EXCLUDING_OPR_ATTRS_BYPASS );
        
        // As the target entry does not exist yet and so
        // its subentry operational attributes are not there,
        // we need to construct an entry to represent it
        // at least with minimal requirements which are object class
        // and access control subentry operational attributes.
        SubentryService subentryService = ( SubentryService ) chain.get( StartupConfiguration.SUBENTRY_SERVICE_NAME );
        Attributes subentryAttrs = subentryService.getSubentryAttributes( newName, importedEntry );
        NamingEnumeration attrList = importedEntry.getAll();
        
        while ( attrList.hasMore() )
        {
            subentryAttrs.put( ( Attribute ) attrList.next() );
        }
        
        Collection<ACITuple> destTuples = new HashSet<ACITuple>();
        // Import permission is only valid for prescriptive ACIs
        addPerscriptiveAciTuples( proxy, destTuples, newName, subentryAttrs );
        // Evaluate the target context to see whether it
        // allows an entry named newName to be imported as a subordinate.
        engine.checkPermission( proxy, userGroups, principalDn, principal.getAuthenticationLevel(), newName, null,
            null, IMPORT_PERMS, destTuples, subentryAttrs );


        next.moveAndRename( moveAndRenameContext );
        tupleCache.subentryRenamed( oriChildName, newName );
        groupCache.groupRenamed( oriChildName, newName );
    }


    public void move( NextInterceptor next, OperationContext moveContext ) throws NamingException
    {
        LdapDN oriChildName = moveContext.getDn();
        LdapDN newParentName = ((MoveOperationContext)moveContext).getParent();
        
        // Access the principal requesting the operation, and bypass checks if it is the admin
        Invocation invocation = InvocationStack.getInstance().peek();
        PartitionNexusProxy proxy = invocation.getProxy();
        Attributes entry = proxy.lookup( new LookupOperationContext( oriChildName ), PartitionNexusProxy.LOOKUP_BYPASS );
        LdapDN newName = ( LdapDN ) newParentName.clone();
        newName.add( oriChildName.get( oriChildName.size() - 1 ) );
        LdapPrincipal principal = ( ( ServerContext ) invocation.getCaller() ).getPrincipal();
        LdapDN principalDn = principal.getJndiName();

        // bypass authz code if we are disabled
        if ( !enabled )
        {
            next.move( moveContext );
            return;
        }

        // bypass authz code but manage caches if operation is performed by the admin
        if ( isPrincipalAnAdministrator( principalDn ) )
        {
            next.move( moveContext );
            tupleCache.subentryRenamed( oriChildName, newName );
            groupCache.groupRenamed( oriChildName, newName );
            return;
        }

        Set userGroups = groupCache.getGroups( principalDn.toString() );
        Collection<ACITuple> tuples = new HashSet<ACITuple>();
        addPerscriptiveAciTuples( proxy, tuples, oriChildName, entry );
        addEntryAciTuples( tuples, entry );
        addSubentryAciTuples( proxy, tuples, oriChildName, entry );

        engine.checkPermission( proxy, userGroups, principalDn, principal.getAuthenticationLevel(), oriChildName, null,
            null, EXPORT_PERMS, tuples, entry );
        
        // Get the entry again without operational attributes
        // because access control subentry operational attributes
        // will not be valid at the new location.
        // This will certainly be fixed by the SubentryService,
        // but after this service.
        Attributes importedEntry = proxy.lookup( new LookupOperationContext( oriChildName ), 
            PartitionNexusProxy.LOOKUP_EXCLUDING_OPR_ATTRS_BYPASS );
        // As the target entry does not exist yet and so
        // its subentry operational attributes are not there,
        // we need to construct an entry to represent it
        // at least with minimal requirements which are object class
        // and access control subentry operational attributes.
        SubentryService subentryService = ( SubentryService ) chain.get( StartupConfiguration.SUBENTRY_SERVICE_NAME );
        Attributes subentryAttrs = subentryService.getSubentryAttributes( newName, importedEntry );
        NamingEnumeration attrList = importedEntry.getAll();
        
        while ( attrList.hasMore() )
        {
            subentryAttrs.put( ( Attribute ) attrList.next() );
        }
        
        Collection<ACITuple> destTuples = new HashSet<ACITuple>();
        // Import permission is only valid for prescriptive ACIs
        addPerscriptiveAciTuples( proxy, destTuples, newName, subentryAttrs );
        // Evaluate the target context to see whether it
        // allows an entry named newName to be imported as a subordinate.
        engine.checkPermission( proxy, userGroups, principalDn, principal.getAuthenticationLevel(), newName, null,
            null, IMPORT_PERMS, destTuples, subentryAttrs );

        next.move( moveContext );
        tupleCache.subentryRenamed( oriChildName, newName );
        groupCache.groupRenamed( oriChildName, newName );
    }

    public NamingEnumeration list( NextInterceptor next, OperationContext opContext ) throws NamingException
    {
        Invocation invocation = InvocationStack.getInstance().peek();
        ServerLdapContext ctx = ( ServerLdapContext ) invocation.getCaller();
        LdapPrincipal user = ctx.getPrincipal();
        NamingEnumeration e = next.list( opContext );
        
        if ( isPrincipalAnAdministrator( user.getJndiName() ) || !enabled )
        {
            return e;
        }
        
        AuthorizationFilter authzFilter = new AuthorizationFilter();
        return new SearchResultFilteringEnumeration( e, DEFAULT_SEARCH_CONTROLS, invocation, authzFilter, "List authorization Filter" );
    }


    public NamingEnumeration<SearchResult> search( NextInterceptor next, OperationContext opContext ) throws NamingException
    {
        Invocation invocation = InvocationStack.getInstance().peek();
        ServerLdapContext ctx = ( ServerLdapContext ) invocation.getCaller();
        LdapPrincipal user = ctx.getPrincipal();
        LdapDN principalDn = user.getJndiName();
        NamingEnumeration<SearchResult> e = next.search( opContext );

        boolean isSubschemaSubentryLookup = subschemaSubentryDn.equals( opContext.getDn().getNormName() );
        SearchControls searchCtls = ((SearchOperationContext)opContext).getSearchControls();
        boolean isRootDSELookup = opContext.getDn().size() == 0 && searchCtls.getSearchScope() == SearchControls.OBJECT_SCOPE;

        if ( isPrincipalAnAdministrator( principalDn ) || !enabled || isRootDSELookup || isSubschemaSubentryLookup )
        {
            return e;
        }
        
        AuthorizationFilter authzFilter = new AuthorizationFilter();
        return new SearchResultFilteringEnumeration( e, searchCtls, invocation, authzFilter, "Search authorization Filter" );
    }

    
    public final boolean isPrincipalAnAdministrator( LdapDN principalDn ) throws NamingException
    {
        return groupCache.isPrincipalAnAdministrator( principalDn );
    }
    

    public boolean compare( NextInterceptor next, OperationContext opContext ) throws NamingException
    {
    	CompareOperationContext ctx = (CompareOperationContext)opContext;
    	LdapDN name = ctx.getDn();
    	String oid = ctx.getOid();
    	Object value = ctx.getValue();
    	
        // Access the principal requesting the operation, and bypass checks if it is the admin
        Invocation invocation = InvocationStack.getInstance().peek();
        PartitionNexusProxy proxy = invocation.getProxy();
        Attributes entry = proxy.lookup( 
        		new LookupOperationContext( name ), 
        		PartitionNexusProxy.LOOKUP_BYPASS );

        LdapPrincipal principal = ( ( ServerContext ) invocation.getCaller() ).getPrincipal();
        LdapDN principalDn = principal.getJndiName();

        if ( isPrincipalAnAdministrator( principalDn ) || !enabled )
        {
            return next.compare( opContext );
        }

        Set userGroups = groupCache.getGroups( principalDn.toNormName() );
        Collection<ACITuple> tuples = new HashSet<ACITuple>();
        addPerscriptiveAciTuples( proxy, tuples, name, entry );
        addEntryAciTuples( tuples, entry );
        addSubentryAciTuples( proxy, tuples, name, entry );

        engine.checkPermission( proxy, userGroups, principalDn, principal.getAuthenticationLevel(), name, null, null,
            READ_PERMS, tuples, entry );
        engine.checkPermission( proxy, userGroups, principalDn, principal.getAuthenticationLevel(), name, oid, value,
            COMPARE_PERMS, tuples, entry );

        return next.compare( opContext );
    }


    public LdapDN getMatchedName ( NextInterceptor next, OperationContext opContext ) throws NamingException
    {
        // Access the principal requesting the operation, and bypass checks if it is the admin
        Invocation invocation = InvocationStack.getInstance().peek();
        PartitionNexusProxy proxy = invocation.getProxy();
        LdapPrincipal principal = ( ( ServerContext ) invocation.getCaller() ).getPrincipal();
        LdapDN principalDn = principal.getJndiName();
        
        if ( isPrincipalAnAdministrator( principalDn ) || !enabled )
        {
            return next.getMatchedName( opContext );
        }

        // get the present matched name
        Attributes entry;
        LdapDN matched = next.getMatchedName( opContext );

        // check if we have disclose on error permission for the entry at the matched dn
        // if not remove rdn and check that until nothing is left in the name and return
        // that but if permission is granted then short the process and return the dn
        while ( matched.size() > 0 )
        {
            entry = proxy.lookup( new LookupOperationContext( matched ), PartitionNexusProxy.GETMATCHEDDN_BYPASS );
            Set userGroups = groupCache.getGroups( principalDn.toString() );
            Collection<ACITuple> tuples = new HashSet<ACITuple>();
            addPerscriptiveAciTuples( proxy, tuples, matched, entry );
            addEntryAciTuples( tuples, entry );
            addSubentryAciTuples( proxy, tuples, matched, entry );

            if ( engine.hasPermission( proxy, userGroups, principalDn, principal.getAuthenticationLevel(), matched, null,
                null, MATCHEDNAME_PERMS, tuples, entry ) )
            {
                return matched;
            }

            matched.remove( matched.size() - 1 );
        }

        return matched;
    }


    public void cacheNewGroup( LdapDN name, Attributes entry ) throws NamingException
    {
        groupCache.groupAdded( name, entry );
    }


    private boolean filter( Invocation invocation, LdapDN normName, SearchResult result ) throws NamingException
    {
        /*
         * First call hasPermission() for entry level "Browse" and "ReturnDN" perm
         * tests.  If we hasPermission() returns false we immediately short the
         * process and return false.
         */
        Attributes entry = invocation.getProxy().lookup( new LookupOperationContext( normName ), PartitionNexusProxy.LOOKUP_BYPASS );
        ServerLdapContext ctx = ( ServerLdapContext ) invocation.getCaller();
        LdapDN userDn = ctx.getPrincipal().getJndiName();
        Set userGroups = groupCache.getGroups( userDn.toNormName() );
        Collection<ACITuple> tuples = new HashSet<ACITuple>();
        addPerscriptiveAciTuples( invocation.getProxy(), tuples, normName, entry );
        addEntryAciTuples( tuples, entry );
        addSubentryAciTuples( invocation.getProxy(), tuples, normName, entry );

        if ( !engine.hasPermission( invocation.getProxy(), userGroups, userDn, ctx.getPrincipal()
            .getAuthenticationLevel(), normName, null, null, SEARCH_ENTRY_PERMS, tuples, entry ) )
        {
            return false;
        }

        /*
         * For each attribute type we check if access is allowed to the type.  If not
         * the attribute is yanked out of the entry to be returned.  If permission is
         * allowed we move on to check if the values are allowed.  Values that are
         * not allowed are removed from the attribute.  If the attribute has no more
         * values remaining then the entire attribute is removed.
         */
        NamingEnumeration idList = result.getAttributes().getIDs();

        while ( idList.hasMore() )
        {
            // if attribute type scope access is not allowed then remove the attribute and continue
            String id = ( String ) idList.next();
            Attribute attr = result.getAttributes().get( id );
        
            if ( !engine.hasPermission( invocation.getProxy(), userGroups, userDn, ctx.getPrincipal()
                .getAuthenticationLevel(), normName, attr.getID(), null, SEARCH_ATTRVAL_PERMS, tuples, entry ) )
            {
                result.getAttributes().remove( attr.getID() );

                if ( attr.size() == 0 )
                {
                    result.getAttributes().remove( attr.getID() );
                }
                continue;
            }

            // attribute type scope is ok now let's determine value level scope
            for ( int ii = 0; ii < attr.size(); ii++ )
            {
                if ( !engine.hasPermission( invocation.getProxy(), userGroups, userDn, ctx.getPrincipal()
                    .getAuthenticationLevel(), normName, attr.getID(), attr.get( ii ), SEARCH_ATTRVAL_PERMS, tuples,
                    entry ) )
                {
                    attr.remove( ii );

                    if ( ii > 0 )
                    {
                        ii--;
                    }
                }
            }
        }

        return true;
    }


    /**
     * WARNING: create one of these filters fresh every time for each new search.
     */
    class AuthorizationFilter implements SearchResultFilter
    {
        public boolean accept( Invocation invocation, SearchResult result, SearchControls controls )
            throws NamingException
        {
            LdapDN normName = parseNormalized( result.getName() );
            return filter( invocation, normName, result );
        }
    }
}
