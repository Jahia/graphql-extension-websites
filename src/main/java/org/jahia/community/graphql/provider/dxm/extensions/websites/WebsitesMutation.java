package org.jahia.community.graphql.provider.dxm.extensions.websites;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import graphql.annotations.annotationTypes.GraphQLTypeExtension;
import org.jahia.modules.graphql.provider.dxm.admin.GqlJahiaAdminMutation;

@GraphQLTypeExtension(GqlJahiaAdminMutation.class)
@GraphQLDescription("Website lifecycle administrative mutations")
public class WebsitesMutation {

    private WebsitesMutation() {
    }

    @GraphQLField
    @GraphQLName("websites")
    @GraphQLDescription("Website lifecycle administrative mutations")
    public static WebsitesAdminMutation websites() {
        return new WebsitesAdminMutation();
    }
}
