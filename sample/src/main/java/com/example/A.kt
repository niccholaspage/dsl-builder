import com.example.AClassBuilder
import kotlin.random.Random

fun main() {
    val builder = AClassBuilder()

    builder.apply {
        cooldown = 5.0
        cooldown {
            Random.nextDouble() * 500
        }
        rollingCooldown {
            Random.nextDouble() * 1000
        }
    }
}


