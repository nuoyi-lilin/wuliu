package net.lab1024.smartadmin.config;

import com.github.xiaoymin.knife4j.spring.annotations.EnableKnife4j;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import io.swagger.annotations.Api;
import lombok.extern.slf4j.Slf4j;
import net.lab1024.smartadmin.constant.SwaggerTagConst;
import net.lab1024.smartadmin.interceptor.SmartAuthenticationInterceptor;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import springfox.documentation.RequestHandler;
import springfox.documentation.builders.ApiInfoBuilder;
import springfox.documentation.builders.ParameterBuilder;
import springfox.documentation.builders.PathSelectors;
import springfox.documentation.builders.RequestHandlerSelectors;
import springfox.documentation.schema.ModelRef;
import springfox.documentation.service.*;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spi.service.contexts.SecurityContext;
import springfox.documentation.spring.web.plugins.Docket;
import springfox.documentation.swagger2.annotations.EnableSwagger2;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * [ ??????SwaggerTagConst?????????????????????Swagger group ]
 *
 * @author yandanyang
 * @version 1.0
 * @company 1024lab.net
 * @copyright (c) 2018 1024lab.netInc. All rights reserved.
 * @date 2019/8/7 0007 ?????? 19:20
 * @since JDK1.8
 */
@Slf4j
@EnableSwagger2
@EnableKnife4j
@Configuration
@Profile({"dev", "sit", "pre", "prod"})
public class SmartSwaggerDynamicGroupConfig implements EnvironmentAware, BeanDefinitionRegistryPostProcessor {

    /**
     * ????????????
     */
    private String apiGroupName;

    /**
     * ????????????
     */
    private String title;

    /**
     * ????????????
     */
    private String description;

    /**
     * api??????
     */
    private String version;

    /**
     * service url
     */
    private String serviceUrl;

    /**
     * controller ?????????
     */
    private String packAge;

    private int groupIndex = 0;

    private String groupName = "default";

    private final List<String> groupList = Lists.newArrayList();

    private final Map<String, List<String>> groupMap = Maps.newHashMap();

    @Override
    public void setEnvironment(Environment environment) {
        this.apiGroupName = environment.getProperty("swagger.apiGroupName");
        this.title = environment.getProperty("swagger.title");
        this.description = environment.getProperty("swagger.description");
        this.version = environment.getProperty("swagger.version");
        this.serviceUrl = environment.getProperty("swagger.serviceUrl");
        this.packAge = environment.getProperty("swagger.packAge");
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        this.groupBuild();
        for (Map.Entry<String, List<String>> entry : groupMap.entrySet()) {
            String group = entry.getKey();
            BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(Docket.class, this::baseDocket);
            BeanDefinition beanDefinition = builder.getRawBeanDefinition();
            registry.registerBeanDefinition(group + "Api", beanDefinition);
        }
    }

    private void groupBuild() {
        Class clazz = SwaggerTagConst.class;
        Class[] innerClazz = clazz.getDeclaredClasses();
        for (Class cls : innerClazz) {
            String group = cls.getSimpleName();
            List<String> apiTags = Lists.newArrayList();
            Field[] fields = cls.getDeclaredFields();
            for (Field field : fields) {
                boolean isFinal = Modifier.isFinal(field.getModifiers());
                if (isFinal) {
                    try {
                        apiTags.add(field.get(null).toString());
                    } catch (Exception e) {
                        log.error("", e);
                    }
                }
            }
            groupList.add(group);
            groupMap.put(group, apiTags);
        }
    }

    private Docket baseDocket() {
        // ?????????????????? token
        ParameterBuilder tokenPar = new ParameterBuilder();
        Parameter parameter = tokenPar.name(SmartAuthenticationInterceptor.TOKEN_NAME)
                .description("token")
                .modelRef(new ModelRef("string"))
                .parameterType("header")
                .defaultValue("")
                .required(false)
                .build();

        // ????????????????????????
        Predicate<RequestHandler> controllerPredicate = getControllerPredicate();
        // controller ?????????
        Predicate<RequestHandler> controllerPackage = RequestHandlerSelectors.basePackage(packAge);
        return new Docket(DocumentationType.SWAGGER_2)
                .groupName(groupName)
                .forCodeGeneration(true)
                .select()
                .apis(controllerPackage)
                .apis(controllerPredicate)
                .paths(PathSelectors.any())
                .build()
                .apiInfo(this.serviceApiInfo())
                .securitySchemes(securitySchemes())
                .securityContexts(securityContexts())
                .globalOperationParameters(Lists.newArrayList(parameter));
    }

    private List<ApiKey> securitySchemes() {
        List<ApiKey> apiKeyList = new ArrayList<>();
        apiKeyList.add(new ApiKey("x-access-token", "x-access-token", "header"));
        return apiKeyList;
    }

    private List<SecurityContext> securityContexts() {
        List<SecurityContext> securityContexts = new ArrayList<>();
        securityContexts.add(
                SecurityContext.builder()
                        .securityReferences(defaultAuth())
                        .forPaths(PathSelectors.any())
                        .build());
        return securityContexts;
    }

    List<SecurityReference> defaultAuth() {
        AuthorizationScope authorizationScope = new AuthorizationScope("global", "accessEverything");
        AuthorizationScope[] authorizationScopes = new AuthorizationScope[1];
        authorizationScopes[0] = authorizationScope;
        List<SecurityReference> securityReferences = new ArrayList<>();
        securityReferences.add(new SecurityReference("x-access-token", authorizationScopes));
        return securityReferences;
    }

    private Predicate<RequestHandler> getControllerPredicate() {
        groupName = groupList.get(groupIndex);
        List<String> apiTags = groupMap.get(groupName);
        Predicate<RequestHandler> methodPredicate = (input) -> {
            Api api = null;
            Optional<Api> apiOptional = input.findControllerAnnotation(Api.class);
            if (apiOptional.isPresent()) {
                api = apiOptional.get();
            }
            List<String> tags = Arrays.asList(api.tags());
            if (api != null && apiTags.containsAll(tags)) {
                return true;
            }
            return false;
        };
        groupIndex++;
        return Predicates.or(
                Predicates.and(RequestHandlerSelectors.withClassAnnotation(RestController.class), methodPredicate),
                Predicates.and(
                        RequestHandlerSelectors.withMethodAnnotation(ResponseBody.class), methodPredicate)
        );
    }

    private ApiInfo serviceApiInfo() {
        return new ApiInfoBuilder()
                .title(title)
                .description(description)
                .version(version)
                .license("Apache License Version 2.0")
                .contact(new Contact("1024???????????????", "http://www.1024lab.net", ""))
                .termsOfServiceUrl(serviceUrl)
                .build();
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory configurableListableBeanFactory) throws BeansException {

    }


}
