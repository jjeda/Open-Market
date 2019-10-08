package me.jjeda.mall.orders.controller;

import lombok.RequiredArgsConstructor;
import me.jjeda.mall.accounts.domain.Account;
import me.jjeda.mall.common.CurrentUser;
import me.jjeda.mall.orders.domain.Order;
import me.jjeda.mall.orders.dto.OrderDto;
import me.jjeda.mall.orders.service.OrderService;
import org.springframework.hateoas.Resource;
import org.springframework.hateoas.mvc.ControllerLinkBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import java.net.URI;
import java.util.Objects;

import static org.springframework.hateoas.mvc.ControllerLinkBuilder.linkTo;

@Controller
@RequiredArgsConstructor
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public ResponseEntity createOrder(@RequestBody OrderDto orderDto, @CurrentUser Account account) {
        Order order = orderService.createOrder(orderDto, account);
        ControllerLinkBuilder selfLinkBuilder = linkTo(OrderController.class).slash(order.getId());
        URI uri = selfLinkBuilder.toUri();
        Resource<Order> orderResource = new Resource<>(order);
        orderResource.add(selfLinkBuilder.withSelfRel());
        orderResource.add(selfLinkBuilder.withRel("cancel-order"));

        return ResponseEntity.created(uri).body(order);
    }

    @GetMapping("/{orderId}")
    public ResponseEntity getOrder(@PathVariable Long orderId, @CurrentUser Account account) {
        Order order = orderService.getOrder(orderId);

        if (!Objects.equals(order.getAccount(), account)) {
            return new ResponseEntity(HttpStatus.UNAUTHORIZED);
        }

        Resource<Order> orderResource = new Resource<>(order);
        orderResource.add(linkTo(OrderController.class).slash(order.getId()).withSelfRel());
        orderResource.add(linkTo(OrderController.class).slash(order.getId()).withRel("cancel-order"));

        return ResponseEntity.ok(orderResource);
    }

    @DeleteMapping("/{orderId}")
    public ResponseEntity cancelOrder(@PathVariable Long orderId, @CurrentUser Account account) {
        Order order = orderService.getOrder(orderId);

        if (!Objects.equals(order.getAccount(), account)) {
            return new ResponseEntity(HttpStatus.UNAUTHORIZED);
        }

        order = orderService.cancelOrder(orderId);

        Resource<Order> orderResource = new Resource<>(order);
        orderResource.add(linkTo(OrderController.class).withRel("create-order"));

        return ResponseEntity.ok(orderResource);
    }
}