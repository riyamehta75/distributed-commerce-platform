package com.example.distributedcommerce.apigateway;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import com.example.distributedcommerce.security.JwtTokenService;
@SpringBootApplication
@Import(JwtTokenService.class)
public class ApiGatewayApplication { public static void main(String[] args){ SpringApplication.run(ApiGatewayApplication.class,args); } }
